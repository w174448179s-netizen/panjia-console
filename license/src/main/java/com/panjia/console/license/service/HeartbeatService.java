package com.panjia.console.license.service;

import tools.jackson.databind.ObjectMapper;
import com.panjia.console.common.enums.*;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.common.util.AuthCodeGenerator;
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
 * </ul>
 * <p>
 * ★ P0-B 修复（心跳续签）：
 * 校验全部通过（clientMode=NORMAL）且 token 剩余寿命低于续签阈值
 * （tokenRenewThresholdDays，默认 7 天）时，基于当前授权重签 JWT 并随心跳
 * 响应的 token 字段下发。客户端 LicenseServiceImpl.renewToken 验签后无感替换。
 * 受限（RESTRICT）客户端不续签——不给受限授权延长密码学有效期。
 * <p>
 * 流程（§4.6）：
 * 1. JWT 校验（四步 + licenseVersion⑤ + 黑名单⑥）
 * 2. 指纹比对
 * 3. 服务端自行计算 clientMode
 * 4. 刷新 offlineExpireAt = now + 7d
 * 5. INSERT t_heartbeat_record
 * 6. token 剩余寿命不足 → 重签 JWT 随响应下发
 * 7. 返回 { offlineExpireAt, clientMode, token? }
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
     * @param clientIp      客户端 IP（用于 IP 多实例检测）
     * @param rawJson       原始请求体（用于诊断）
     * @return 心跳响应（offlineExpireAt + clientMode）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public HeartbeatResponse heartbeat(String jwtToken, String instanceId, String fingerprint,
                                       OffsetDateTime reportedAt, Integer currentStores,
                                       Integer currentUsers, String clientIp, String rawJson) {
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

            // 第三步：指纹比对
            // P0-2 修复：客户端 heartbeat 请求里的 fingerprint 已经是 calculateHash() 输出（sha256(hostMachineId|instanceId)）
            //   控制台直接使用客户端传来的哈希作为 fpHash，与 JWT 签发的 fingerprintHash 对齐
            // P2 修复：fingerprint 判空守卫。缺失时直接受限，避免下方 equals 调用 NPE
            fpHash = fingerprint;
            if (fpHash == null || fpHash.isBlank()) {
                clientMode = ClientMode.RESTRICT;
                restrictReason = LicenseErrorCode.FP_MISMATCH;
            } else if (claims.getFpHash() != null && !fpHash.equals(claims.getFpHash())) {
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
        OffsetDateTime offlineExpireAt = now.plusDays(config.getOfflineExpireDays());

        // ★ 第九步（P0-B 修复）：token 自动续签
        //    仅校验全部通过（clientMode=NORMAL）的合法客户端续签；
        //    剩余寿命 < tokenRenewThresholdDays 时重签，客户端 renewToken 无感替换。
        //    阈值必须 ≥ 客户端离线宽限期，保证断网瞬间 token 剩余寿命覆盖整个宽限期。
        String renewedToken = null;
        if (clientMode == ClientMode.NORMAL && claims != null && claims.getExpiresAt() != null) {
            java.time.Duration remaining = java.time.Duration.between(now, claims.getExpiresAt());
            java.time.Duration renewThreshold = java.time.Duration.ofDays(
                    Math.max(1, config.getTokenRenewThresholdDays()));
            if (remaining.compareTo(renewThreshold) < 0) {
                try {
                    LicenseJwtClaims renewClaims = claims.toBuilder()
                            .clientMode(ClientMode.NORMAL.name())
                            .offlineExpireAt(offlineExpireAt)
                            .issuedAt(now)
                            .build();
                    renewedToken = signatureEngine.issueJwt(renewClaims);
                    log.info("Token renewed in heartbeat: authCode={}, remaining={}h < threshold={}h",
                            AuthCodeGenerator.mask(claims.getAuthCode()),
                            remaining.toHours(), renewThreshold.toHours());
                } catch (Exception e) {
                    // 续签失败不阻断心跳本身，下一次心跳会再尝试
                    log.error("Failed to renew token in heartbeat", e);
                }
            }
        }

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
            record.setClientIp(clientIp);
            record.setRaw(rawJson);
            heartbeatRecordMapper.insert(record);
        } catch (Exception e) {
            // 心跳记录写入失败不影响响应（降级处理）
            log.error("Failed to insert heartbeat record", e);
        }

        // ★ IP 多实例检测（在心跳记录写入后执行）
        //    同一授权码在窗口内出现不同 IP → 两阶段拉黑
        if (clientIp != null && !clientIp.isBlank()
                && authCode != null
                && AuthCodeStatus.ACTIVE.name().equals(authCode.getStatus())
                && clientMode == ClientMode.NORMAL) {
            try {
                OffsetDateTime ipWindowStart = now.minusMinutes(config.getIpMismatchWindowMinutes());
                var diffIpRecords = heartbeatRecordMapper.selectRecentByDifferentIp(
                        authCode.getId(), clientIp, ipWindowStart);
                if (!diffIpRecords.isEmpty()) {
                    multiInstanceService.handleIpMismatchDetected(
                            authCode.getId(), authCode.getCustomerNo(), clientIp);
                }
            } catch (Exception e) {
                log.error("IP multi-instance detection failed", e);
            }
        }

        // 返回结果
        return HeartbeatResponse.builder()
                .offlineExpireAt(offlineExpireAt)
                .clientMode(clientMode.name())
                .restrictCode(restrictReason != null ? restrictReason.getCode() : null)
                .token(renewedToken)
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
        /**
         * 续签后的新 JWT（P0-B）。
         * 仅当客户端校验全部通过且 token 剩余寿命低于续签阈值时非空；
         * 客户端收到后验签并无感替换（renewToken）。
         */
        private String token;
    }
}
