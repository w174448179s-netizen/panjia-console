package com.panjia.console.license.security;

import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * JWT 验签器
 * <p>
 * 使用 RSA 公钥验签 RS256 JWT。
 * <p>
 * ★ 注意：此处仅做签名校验和基本结构解析，
 * 业务层校验（指纹比对、版本范围、licenseVersion、黑名单等）由 CheckService 统一处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtVerifier {

    private final JwtConfigProperties config;

    /**
     * 验签 JWT 并解析 claims
     * <p>
     * ★ S-5 修复：验签后显式断言 alg=RS256（与客户端 LicenseVerifier 对齐）。
     * jjwt 解析器本身对密钥/算法匹配有隐式防护，此处显式断言作为纵深防御，
     * 防止未来依赖升级或解析配置变化导致 alg confusion（如 HS256 用公钥当 HMAC 密钥）。
     *
     * @param jwtToken JWT 字符串
     * @param publicKey 公钥（PEM 格式或 Base64 编码）
     * @return 解析后的 claims
     * @throws LicenseException 签名无效时抛出 SIGNATURE_INVALID
     */
    public Jws<Claims> verify(String jwtToken, PublicKey publicKey) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)
                    .clock(() -> new java.util.Date(System.currentTimeMillis()))
                    .clockSkewSeconds(config.getClockSkewSeconds())
                    .build()
                    .parseSignedClaims(jwtToken);
            // 显式算法断言：非 RS256 一律拒绝
            String alg = jws.getHeader().getAlgorithm();
            if (!"RS256".equals(alg)) {
                log.warn("JWT rejected: unexpected algorithm [{}], expected RS256", alg);
                throw new LicenseException(LicenseErrorCode.SIGNATURE_INVALID);
            }
            return jws;
        } catch (JwtException e) {
            log.warn("JWT signature verification failed: {}", e.getMessage());
            throw new LicenseException(LicenseErrorCode.SIGNATURE_INVALID, e);
        }
    }

    /**
     * 从 PEM 格式公钥字符串解析 PublicKey
     *
     * @param pemPublicKey PEM 格式公钥（可含 BEGIN/END 标记）
     * @return PublicKey 对象
     */
    public static PublicKey parsePublicKey(String pemPublicKey) {
        try {
            // 去除 PEM 标记和空白
            String base64Key = pemPublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse public key", e);
        }
    }

    /**
     * 从 claims 中提取指定字段（安全获取，避免空指针）
     */
    public String getStringClaim(Claims claims, String key) {
        Object value = claims.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 从 claims 中提取 Integer 字段
     */
    public Integer getIntClaim(Claims claims, String key) {
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
}
