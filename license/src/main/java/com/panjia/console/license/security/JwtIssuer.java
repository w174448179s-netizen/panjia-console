package com.panjia.console.license.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * JWT 签发器
 * <p>
 * 从 JKS 密钥库加载 RSA 私钥，使用 RS256 算法签发 JWT。
 * <p>
 * ★ 安全铁律（H5）：
 * <ul>
 *   <li>私钥仅本类加载，永远不出进程、不入库、不进日志、不打印到控制台</li>
 *   <li>JKS 文件权限必须为 600</li>
 *   <li>密码从环境变量注入，禁止明文写配置</li>
 * </ul>
 * <p>
 * 全系统只有一处签发 JWT：activate 接口（§4.4）。
 * heartbeat / check 不签发新 JWT，只返回 offlineExpireAt。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtIssuer {

    private final JwtConfigProperties config;

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private int currentKeyVersion = 1;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 初始化时加载 JKS 密钥库
     */
    @PostConstruct
    public void init() {
        try {
            KeyStore keyStore = KeyStore.getInstance("JKS");
            char[] password = config.getJksPassword().toCharArray();

            try (FileInputStream fis = new FileInputStream(config.getJksPath())) {
                keyStore.load(fis, password);
            }

            // 加载私钥
            privateKey = (PrivateKey) keyStore.getKey(
                    config.getKeyAlias(),
                    config.getKeyPassword().toCharArray()
            );

            // 加载公钥（从证书链中获取）
            Certificate cert = keyStore.getCertificate(config.getKeyAlias());
            publicKey = cert.getPublicKey();

            log.info("JWT issuer initialized with key alias: {}", config.getKeyAlias());
            log.info("JKS path: {} (loaded successfully)", config.getJksPath());

        } catch (Exception e) {
            log.error("Failed to load JKS keystore from: {}", config.getJksPath(), e);
            throw new IllegalStateException("Failed to initialize JWT issuer: " + e.getMessage(), e);
        }
    }

    /**
     * 签发 JWT
     * <p>
     * ★ 全系统只有这一处签发 JWT（activate 接口调用）。
     *
     * @param claims JWT 载荷
     * @return JWT 字符串
     */
    public String issueToken(LicenseJwtClaims claims) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime exp = now.plusDays(config.getJwtExpireDays());

        return Jwts.builder()
                .subject(claims.getAuthCode())
                .claim("authCode", claims.getAuthCode())
                .claim("customerNo", claims.getCustomerNo())
                .claim("company", claims.getCompany())
                .claim("plan", claims.getPlan())
                .claim("fpHash", claims.getFpHash())
                .claim("maxStores", claims.getMaxStores())
                .claim("maxUsers", claims.getMaxUsers())
                .claim("capabilities", claims.getCapabilities())
                .claim("startDate", claims.getStartDate() != null ? claims.getStartDate().toString() : null)
                .claim("endDate", claims.getEndDate() != null ? claims.getEndDate().toString() : null)
                .claim("maintenanceEndDate", claims.getMaintenanceEndDate() != null ? claims.getMaintenanceEndDate().toString() : null)
                .claim("minSupportedVersion", claims.getMinSupportedVersion())
                .claim("maxSupportedVersion", claims.getMaxSupportedVersion())
                .claim("keyVersion", claims.getKeyVersion() != null ? claims.getKeyVersion() : currentKeyVersion)
                .claim("licenseVersion", claims.getLicenseVersion())
                .claim("clientMode", claims.getClientMode())
                .claim("offlineExpireAt", claims.getOfflineExpireAt() != null
                        ? Date.from(claims.getOfflineExpireAt().toInstant()) : null)
                .issuedAt(Date.from(now.toInstant()))
                .expiration(Date.from(exp.toInstant()))
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    /**
     * 解析 JWT 并转换为 LicenseJwtClaims DTO
     * <p>
     * 注意：此方法不验签，仅做结构解析。验签由 JwtVerifier 负责。
     *
     * @param jws 已验签的 JWT
     * @return LicenseJwtClaims DTO
     */
    public LicenseJwtClaims parseClaims(Jws<Claims> jws) {
        Claims claims = jws.getPayload();

        List<String> capabilities = null;
        Object capObj = claims.get("capabilities");
        if (capObj != null) {
            try {
                capabilities = objectMapper.convertValue(capObj, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse capabilities claim: {}", e.getMessage());
            }
        }

        return LicenseJwtClaims.builder()
                .authCode(getString(claims, "authCode"))
                .customerNo(getString(claims, "customerNo"))
                .company(getString(claims, "company"))
                .plan(getString(claims, "plan"))
                .fpHash(getString(claims, "fpHash"))
                .maxStores(getInt(claims, "maxStores"))
                .maxUsers(getInt(claims, "maxUsers"))
                .capabilities(capabilities)
                .startDate(parseDate(getString(claims, "startDate")))
                .endDate(parseDate(getString(claims, "endDate")))
                .maintenanceEndDate(parseDate(getString(claims, "maintenanceEndDate")))
                .minSupportedVersion(getString(claims, "minSupportedVersion"))
                .maxSupportedVersion(getString(claims, "maxSupportedVersion"))
                .keyVersion(getInt(claims, "keyVersion"))
                .licenseVersion(getInt(claims, "licenseVersion"))
                .clientMode(getString(claims, "clientMode"))
                .offlineExpireAt(parseOffsetDateTime(getString(claims, "offlineExpireAt")))
                .issuedAt(claims.getIssuedAt() != null
                        ? claims.getIssuedAt().toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime()
                        : null)
                .expiresAt(claims.getExpiration() != null
                        ? claims.getExpiration().toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime()
                        : null)
                .build();
    }

    /**
     * 获取当前公钥
     */
    public PublicKey getPublicKey() {
        return publicKey;
    }

    /**
     * 获取当前密钥版本号
     */
    public int getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    private String getString(Claims claims, String key) {
        Object value = claims.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getInt(Claims claims, String key) {
        Object value = claims.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return LocalDate.parse(value);
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return OffsetDateTime.parse(value);
    }
}
