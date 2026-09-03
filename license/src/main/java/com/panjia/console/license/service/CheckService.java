package com.panjia.console.license.service;

import com.panjia.console.common.enums.*;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.domain.FingerprintBinding;
import com.panjia.console.license.domain.LicenseContent;
import com.panjia.console.license.mapper.AuthCodeMapper;
import com.panjia.console.license.mapper.FingerprintBindingMapper;
import com.panjia.console.license.mapper.LicenseContentMapper;
import com.panjia.console.license.security.JwtConfigProperties;
import com.panjia.console.license.security.LicenseJwtClaims;
import com.panjia.console.license.security.SignatureEngine;
import com.panjia.console.common.util.FingerprintUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 实时校验服务（check）
 * <p>
 * 客户端调用 /api/auth/check 进行实时授权校验。
 * <p>
 * ★ 重要边界（§4.8）：
 * <ul>
 *   <li>不判断在线/离线状态，不查心跳新鲜度</li>
 *   <li>决策权 100% 在客户端本地，服务端只返回校验结果</li>
 *   <li>check 不签发新 JWT，只返回 clientMode + capabilities</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckService {

    private final SignatureEngine signatureEngine;
    private final AuthCodeMapper authCodeMapper;
    private final FingerprintBindingMapper fingerprintBindingMapper;
    private final LicenseContentMapper licenseContentMapper;
    private final BlacklistService blacklistService;
    private final JwtConfigProperties config;

    /**
     * 实时校验授权
     *
     * @param jwtToken       JWT
     * @param productVersion 产品版本
     * @param currentStores  当前门店数
     * @param currentUsers   当前用户数
     * @return 校验结果
     */
    public CheckResponse check(String jwtToken, String productVersion,
                               Integer currentStores, Integer currentUsers) {
        ClientMode clientMode = ClientMode.NORMAL;
        LicenseErrorCode restrictReason = null;
        LicenseJwtClaims claims = null;
        AuthCode authCode = null;
        List<String> capabilities = Collections.emptyList();

        try {
            // 第一步：JWT 验签 + 解析
            claims = signatureEngine.verifyAndParse(jwtToken);
            String authCodeStr = claims.getAuthCode();

            // 第二步：查询授权码
            authCode = authCodeMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuthCode>()
                            .eq(AuthCode::getAuthCode, authCodeStr)
                            .last("LIMIT 1")
            );

            if (authCode == null) {
                throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
            }

            // 第三步：licenseVersion 一致性校验（⑤）
            LicenseContent currentContent = licenseContentMapper.selectCurrent(authCode.getId());
            if (currentContent == null
                    || !currentContent.getLicenseVersion().equals(claims.getLicenseVersion())) {
                clientMode = ClientMode.RESTRICT;
                restrictReason = LicenseErrorCode.TOKEN_REVOKED;
            } else {
                capabilities = parseCapabilities(currentContent.getCapabilities());
            }

            // 第四步：授权状态校验
            if (clientMode == ClientMode.NORMAL) {
                String status = authCode.getStatus();
                if (AuthCodeStatus.REVOKED.name().equals(status)) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.TOKEN_REVOKED;
                } else if (authCode.getEndDate() != null
                        && authCode.getEndDate().isBefore(LocalDate.now())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.AUTH_EXPIRED;
                }
            }

            // 第五步：版本范围校验
            if (clientMode == ClientMode.NORMAL && productVersion != null) {
                if (!isVersionInRange(productVersion,
                        claims.getMinSupportedVersion(),
                        claims.getMaxSupportedVersion())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.VERSION_OUT_OF_RANGE;
                }
            }

            // 第六步：指纹比对（仅校验 JWT claims 中的 fpHash 与绑定是否一致）
            if (clientMode == ClientMode.NORMAL) {
                FingerprintBinding activeBinding = fingerprintBindingMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FingerprintBinding>()
                                .eq(FingerprintBinding::getAuthCodeId, authCode.getId())
                                .eq(FingerprintBinding::getStatus, FingerprintStatus.ACTIVE.name())
                                .last("LIMIT 1")
                );

                if (activeBinding == null) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.NOT_ACTIVATED;
                } else if (!claims.getFpHash().equals(activeBinding.getFpHash())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.FP_MISMATCH;
                }
            }

            // ★ 第七步：黑名单双重校验（H4）
            if (clientMode == ClientMode.NORMAL) {
                if (blacklistService.isBlacklisted(authCode.getId())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.TOKEN_REVOKED;
                }
            }

            // 第八步：门店数/用户数超限检查（仅警告，不直接受限，由客户端自行处理）
            if (clientMode == ClientMode.NORMAL) {
                if (currentStores != null && claims.getMaxStores() != null
                        && currentStores > claims.getMaxStores()) {
                    log.debug("Stores exceed limit: authCode={}, current={}, max={}",
                            authCodeStr, currentStores, claims.getMaxStores());
                }
                if (currentUsers != null && claims.getMaxUsers() != null
                        && currentUsers > claims.getMaxUsers()) {
                    log.debug("Users exceed limit: authCode={}, current={}, max={}",
                            authCodeStr, currentUsers, claims.getMaxUsers());
                }
            }

        } catch (LicenseException e) {
            if (e.getErrorCode() == LicenseErrorCode.SIGNATURE_INVALID) {
                throw e; // 签名错误直接 401
            }
            clientMode = ClientMode.RESTRICT;
            restrictReason = e.getErrorCode();
        } catch (Exception e) {
            log.error("Check processing error", e);
            clientMode = ClientMode.RESTRICT;
            restrictReason = LicenseErrorCode.INTERNAL_ERROR;
        }

        return CheckResponse.builder()
                .clientMode(clientMode.name())
                .restrictCode(restrictReason != null ? restrictReason.getCode() : null)
                .capabilities(capabilities)
                .maxStores(claims != null ? claims.getMaxStores() : null)
                .maxUsers(claims != null ? claims.getMaxUsers() : null)
                .endDate(claims != null ? claims.getEndDate() : null)
                .build();
    }

    /**
     * 检查版本是否在许可范围内
     */
    private boolean isVersionInRange(String version, String minVersion, String maxVersion) {
        if (version == null || version.isEmpty()) {
            return true; // 未传版本号不校验
        }
        if (minVersion != null && !minVersion.isEmpty()
                && compareVersions(version, minVersion) < 0) {
            return false;
        }
        if (maxVersion != null && !maxVersion.isEmpty()
                && compareVersions(version, maxVersion) > 0) {
            return false;
        }
        return true;
    }

    /**
     * 简单的版本号比较（支持 x.y.z 格式）
     */
    private int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int len = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int n2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析 capabilities JSON
     */
    private List<String> parseCapabilities(String capabilitiesJson) {
        if (capabilitiesJson == null || capabilitiesJson.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
            return mapper.readValue(capabilitiesJson,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("Failed to parse capabilities: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * check 响应 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CheckResponse {
        /** 客户端模式 */
        private String clientMode;
        /** 受限原因错误码 */
        private String restrictCode;
        /** 能力位列表 */
        private List<String> capabilities;
        /** 最大门店数 */
        private Integer maxStores;
        /** 最大用户数 */
        private Integer maxUsers;
        /** 授权到期日期 */
        private LocalDate endDate;
    }
}
