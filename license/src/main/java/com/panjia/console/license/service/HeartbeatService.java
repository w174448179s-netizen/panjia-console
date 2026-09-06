package com.panjia.console.license.service;

import tools.jackson.databind.ObjectMapper;
import com.panjia.console.common.enums.*;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.common.util.AuthCodeGenerator;
import com.panjia.console.common.util.FingerprintUtils;
import com.panjia.console.license.domain.*;
import com.panjia.console.license.mapper.*;
import com.panjia.console.license.security.JwtConfigProperties;
import com.panjia.console.license.security.LicenseJwtClaims;
import com.panjia.console.license.security.SignatureEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 心跳服务
 * <p>
 * 客户端直连 license 模块（/api/auth/heartbeat），customer 不接收心跳。
 * <p>
 * ★ P0-5 铁律（Code Review 必查）：
 * <ul>
 *   <li>心跳请求不含 clientMode，服务端自行计算</li>
 *   <li>客户端只负责执行服务端下发的 clientMode</li>
 *   <li>心跳不刷新 JWT，只返回 offlineExpireAt</li>
 * </ul>
 * <p>
 * 流程（§4.6）：
 * 1. JWT 校验（四步 + licenseVersion⑤ + 黑名单⑥）
 * 2. 指纹比对
 * 3. 服务端自行计算 clientMode
 * 4. 刷新 offlineExpireAt = now + 7d
 * 5. INSERT t_heartbeat_record
 * 6. 返回 { offlineExpireAt, clientMode }
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeartbeatService {

    private final SignatureEngine signatureEngine;
    private final AuthCodeMapper authCodeMapper;
    private final FingerprintBindingMapper fingerprintBindingMapper;
    private final LicenseContentMapper licenseContentMapper;
    private final HeartbeatRecordMapper heartbeatRecordMapper;
    private final BlacklistService blacklistService;
    private final MultiInstanceService multiInstanceService;
    private final JwtConfigProperties config;
    private final ObjectMapper objectMapper;

    /**
     * 处理心跳请求
     *
     * @param jwtToken      JWT
     * @param instanceId    实例 ID
     * @param fingerprint   指纹原文
     * @param reportedAt    客户端上报时间
     * @param currentStores 当前门店数
     * @param currentUsers  当前用户数
     * @param rawJson       原始请求体（用于诊断）
     * @return 心跳响应（offlineExpireAt + clientMode）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public HeartbeatResponse heartbeat(String jwtToken, String instanceId, String fingerprint,
                                       OffsetDateTime reportedAt, Integer currentStores,
                                       Integer currentUsers, String rawJson) {
        OffsetDateTime now = OffsetDateTime.now();
        ClientMode clientMode = ClientMode.NORMAL;
        LicenseErrorCode restrictReason = null;
        LicenseJwtClaims claims = null;
        AuthCode authCode = null;
        String fpHash = null;

        try {
            // 第一步：JWT 验签 + 解析
            claims = signatureEngine.verifyAndParse(jwtToken);
            String authCodeStr = claims.getAuthCode();

            // 第二步：查询授权码（不加锁，心跳是高频读操作）
            authCode = authCodeMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuthCode>()
                            .eq(AuthCode::getAuthCode, authCodeStr)
            );

            if (authCode == null) {
                throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
            }

            // 第三步：重算指纹哈希比对
            fpHash = FingerprintUtils.computeFpHash(authCodeStr, fingerprint, claims.getPlan());
            if (!fpHash.equals(claims.getFpHash())) {
                clientMode = ClientMode.RESTRICT;
                restrictReason = LicenseErrorCode.FP_MISMATCH;
            }

            // 第四步：查当前 ACTIVE 指纹绑定，比对 fpHash
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
                } else if (!fpHash.equals(activeBinding.getFpHash())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.FP_MISMATCH;

                    // ★ 多实例两阶段检测（仅 ACTIVE 状态，REBINDING 跳过）
                    if (AuthCodeStatus.ACTIVE.name().equals(authCode.getStatus())) {
                        multiInstanceService.handleMultiInstanceDetected(
                                authCode.getId(), authCode.getCustomerNo(), fpHash);
                    }
                }
            }

            // 第五步：licenseVersion 一致性校验
            if (clientMode == ClientMode.NORMAL) {
                LicenseContent currentContent = licenseContentMapper.selectCurrent(authCode.getId());
                if (currentContent == null
                        || !currentContent.getLicenseVersion().equals(claims.getLicenseVersion())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.TOKEN_REVOKED;
                }
            }

            // 第六步：授权状态 + 过期判定
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

            // ★ 第七步：黑名单双重校验（H4）
            //    即使 status=ACTIVE，只要在黑名单就拒绝
            if (clientMode == ClientMode.NORMAL) {
                if (blacklistService.isBlacklisted(authCode.getId())) {
                    clientMode = ClientMode.RESTRICT;
                    restrictReason = LicenseErrorCode.TOKEN_REVOKED;
                }
            }

        } catch (LicenseException e) {
            // JWT 签名错误等直接抛给上层（401）
            if (e.getErrorCode() == LicenseErrorCode.SIGNATURE_INVALID) {
                throw e;
            }
            // 其他业务异常 → 受限模式
            clientMode = ClientMode.RESTRICT;
            restrictReason = e.getErrorCode();
        } catch (Exception e) {
            log.error("Heartbeat processing error", e);
            clientMode = ClientMode.RESTRICT;
            restrictReason = LicenseErrorCode.INTERNAL_ERROR;
        }

        // 第八步：计算 offlineExpireAt
        //    注意：心跳只刷新 offlineExpireAt，不签发新 JWT（M1 醒目提示）
        OffsetDateTime offlineExpireAt = now.plusDays(config.getOfflineExpireDays());

        // 第九步：写入心跳记录（即使受限也记录，用于诊断）
        try {
            HeartbeatRecord record = new HeartbeatRecord();
            if (authCode != null) {
                record.setAuthCodeId(authCode.getId());
                record.setCustomerNo(authCode.getCustomerNo());
            } else if (claims != null) {
                record.setCustomerNo(claims.getCustomerNo());
            }
            record.setFpHash(fpHash);
            record.setInstanceId(instanceId);
            record.setReportedAt(reportedAt);
            record.setReceivedAt(now);
            record.setCurrentStores(currentStores);
            record.setCurrentUsers(currentUsers);
            record.setClientMode(clientMode.name());
            record.setRestrictReason(restrictReason != null ? restrictReason.name() : null);
            record.setRaw(rawJson);
            heartbeatRecordMapper.insert(record);
        } catch (Exception e) {
            // 心跳记录写入失败不影响响应（降级处理）
            log.error("Failed to insert heartbeat record", e);
        }

        // 返回结果
        return HeartbeatResponse.builder()
                .offlineExpireAt(offlineExpireAt)
                .clientMode(clientMode.name())
                .restrictCode(restrictReason != null ? restrictReason.getCode() : null)
                .build();
    }

    /**
     * 心跳响应 DTO
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HeartbeatResponse {
        /** 离线宽限截止时间 */
        private OffsetDateTime offlineExpireAt;
        /** 客户端模式（服务端计算） */
        private String clientMode;
        /** 受限原因错误码（仅受限模式时有值） */
        private String restrictCode;
    }
}
