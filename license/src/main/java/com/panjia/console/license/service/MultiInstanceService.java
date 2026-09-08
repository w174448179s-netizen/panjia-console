package com.panjia.console.license.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.console.common.util.TimeUtils;
import com.panjia.console.common.enums.AlertTrigger;
import com.panjia.console.common.enums.BlacklistReason;
import com.panjia.console.license.domain.Blacklist;
import com.panjia.console.license.domain.MultiInstancePending;
import com.panjia.console.license.mapper.BlacklistMapper;
import com.panjia.console.license.mapper.MultiInstancePendingMapper;
import com.panjia.console.license.security.JwtConfigProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 多实例检测服务（两阶段防误杀）
 * <p>
 * 同一 authCode 在窗口(24h)内出现不同 fingerprint：
 * 首次 → INSERT t_multi_instance_pending (confirm_count=1) + 告警(T1_AUTH_FAIL)
 * confirm_count 累加；达到 3 次 → CONFIRMED → INSERT 黑名单(reason=MULTI_INSTANCE) + 告警
 * <p>
 * REBINDING 状态整体跳过此检测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiInstanceService {

    private final MultiInstancePendingMapper pendingMapper;
    private final BlacklistMapper blacklistMapper;
    private final AlertService alertService;
    private final JwtConfigProperties config;

    /**
     * 处理多实例检测
     * <p>
     * 在心跳时发现指纹不匹配时调用。
     * REBINDING 状态下不调用此方法（由调用方保证）。
     *
     * @param authCodeId 授权码 ID
     * @param customerNo 客户编号
     * @param fpHash     新指纹哈希
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void handleMultiInstanceDetected(Long authCodeId, String customerNo, String fpHash) {
        OffsetDateTime now = TimeUtils.now();

        // 查询是否已有该指纹的 pending 记录
        LambdaQueryWrapper<MultiInstancePending> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultiInstancePending::getAuthCodeId, authCodeId)
                .eq(MultiInstancePending::getFpHash, fpHash);
        MultiInstancePending existing = pendingMapper.selectOne(wrapper);

        if (existing != null) {
            // 已有记录 → confirm_count +1
            int newCount = existing.getConfirmCount() + 1;
            existing.setConfirmCount(newCount);
            existing.setLastSeenAt(now);
            existing.setUpdatedAt(now);
            pendingMapper.updateById(existing);

            log.debug("Multi-instance pending count={}, authCodeId={}", newCount, authCodeId);

            // 达到阈值 → 确认拉黑
            if (newCount >= config.getMultiInstanceConfirmCount()) {
                confirmBlacklist(authCodeId, customerNo, fpHash);
            }
        } else {
            // 首次出现 → 插入 pending 记录 + T1 告警
            try {
                MultiInstancePending pending = new MultiInstancePending();
                pending.setAuthCodeId(authCodeId);
                pending.setFpHash(fpHash);
                pending.setConfirmCount(1);
                pending.setFirstSeenAt(now);
                pending.setLastSeenAt(now);
                pendingMapper.insert(pending);
            } catch (DuplicateKeyException e) {
                // 并发插入冲突 → 更新计数
                existing = pendingMapper.selectOne(wrapper);
                if (existing != null) {
                    int newCount = existing.getConfirmCount() + 1;
                    existing.setConfirmCount(newCount);
                    existing.setLastSeenAt(now);
                    existing.setUpdatedAt(now);
                    pendingMapper.updateById(existing);

                    if (newCount >= config.getMultiInstanceConfirmCount()) {
                        confirmBlacklist(authCodeId, customerNo, fpHash);
                    }
                    return;
                }
            }

            // 首次告警（T1_AUTH_FAIL）
            alertService.createAlert(
                    customerNo,
                    authCodeId,
                    "MULTI_INSTANCE_DETECTED",
                    AlertTrigger.T1_AUTH_FAIL.name(),
                    "WARN",
                    "检测到多实例指纹差异",
                    String.format("{\"authCodeId\":%d,\"fpHash\":\"%s\",\"confirmCount\":1}",
                            authCodeId, fpHash.substring(0, Math.min(16, fpHash.length())))
            );

            log.info("Multi-instance detected (first): authCodeId={}", authCodeId);
        }
    }

    /**
     * 确认拉黑（达到阈值后）
     */
    private void confirmBlacklist(Long authCodeId, String customerNo, String fpHash) {
        // 检查是否已在黑名单
        if (blacklistMapper.selectByAuthCodeId(authCodeId) != null) {
            return; // 已在黑名单中
        }

        Blacklist blacklist = new Blacklist();
        blacklist.setAuthCodeId(authCodeId);
        blacklist.setReason(BlacklistReason.MULTI_INSTANCE.name());
        blacklist.setCreatedBy("system");
        try {
            blacklistMapper.insert(blacklist);
        } catch (DuplicateKeyException e) {
            // 并发冲突，已被其他线程插入
            return;
        }

        // 严重告警
        alertService.createAlert(
                customerNo,
                authCodeId,
                "MULTI_INSTANCE_CONFIRMED",
                AlertTrigger.T1_AUTH_FAIL.name(),
                "CRITICAL",
                "多实例确认，已加入黑名单",
                String.format("{\"authCodeId\":%d,\"fpHash\":\"%s\"}",
                        authCodeId, fpHash.substring(0, Math.min(16, fpHash.length())))
        );

        log.warn("Multi-instance confirmed and blacklisted: authCodeId={}", authCodeId);
    }

    /**
     * 处理 IP 多实例检测（同授权码不同 IP 同时心跳）
     * <p>
     * 与指纹检测独立，使用更短的窗口和更低的阈值。
     * pending 记录以 "ip:" 前缀区分。
     * REBINDING 状态下不调用此方法（由调用方保证）。
     *
     * @param authCodeId 授权码 ID
     * @param customerNo 客户编号
     * @param clientIp   当前客户端 IP
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void handleIpMismatchDetected(Long authCodeId, String customerNo, String clientIp) {
        OffsetDateTime now = TimeUtils.now();
        String ipKey = "ip:" + clientIp;

        // 查询是否已有该 IP 的 pending 记录
        LambdaQueryWrapper<MultiInstancePending> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MultiInstancePending::getAuthCodeId, authCodeId)
                .eq(MultiInstancePending::getFpHash, ipKey);
        MultiInstancePending existing = pendingMapper.selectOne(wrapper);

        if (existing != null) {
            int newCount = existing.getConfirmCount() + 1;
            existing.setConfirmCount(newCount);
            existing.setLastSeenAt(now);
            existing.setUpdatedAt(now);
            pendingMapper.updateById(existing);

            log.debug("IP multi-instance pending count={}, authCodeId={}, ip={}", newCount, authCodeId, clientIp);

            if (newCount >= config.getIpMismatchConfirmCount()) {
                confirmIpBlacklist(authCodeId, customerNo, clientIp);
            }
        } else {
            try {
                MultiInstancePending pending = new MultiInstancePending();
                pending.setAuthCodeId(authCodeId);
                pending.setFpHash(ipKey);
                pending.setConfirmCount(1);
                pending.setFirstSeenAt(now);
                pending.setLastSeenAt(now);
                pendingMapper.insert(pending);
            } catch (DuplicateKeyException e) {
                existing = pendingMapper.selectOne(wrapper);
                if (existing != null) {
                    int newCount = existing.getConfirmCount() + 1;
                    existing.setConfirmCount(newCount);
                    existing.setLastSeenAt(now);
                    existing.setUpdatedAt(now);
                    pendingMapper.updateById(existing);

                    if (newCount >= config.getIpMismatchConfirmCount()) {
                        confirmIpBlacklist(authCodeId, customerNo, clientIp);
                    }
                    return;
                }
            }

            alertService.createAlert(
                    customerNo,
                    authCodeId,
                    "IP_MULTI_INSTANCE_DETECTED",
                    AlertTrigger.T1_AUTH_FAIL.name(),
                    "WARN",
                    "检测到同授权码不同 IP 心跳",
                    String.format("{\"authCodeId\":%d,\"clientIp\":\"%s\",\"confirmCount\":1}",
                            authCodeId, clientIp)
            );

            log.info("IP multi-instance detected (first): authCodeId={}, ip={}", authCodeId, clientIp);
        }
    }

    /**
     * IP 多实例确认拉黑
     */
    private void confirmIpBlacklist(Long authCodeId, String customerNo, String clientIp) {
        if (blacklistMapper.selectByAuthCodeId(authCodeId) != null) {
            return;
        }

        Blacklist blacklist = new Blacklist();
        blacklist.setAuthCodeId(authCodeId);
        blacklist.setReason(BlacklistReason.IP_MULTI_INSTANCE.name());
        blacklist.setCreatedBy("system");
        try {
            blacklistMapper.insert(blacklist);
        } catch (DuplicateKeyException e) {
            return;
        }

        alertService.createAlert(
                customerNo,
                authCodeId,
                "IP_MULTI_INSTANCE_CONFIRMED",
                AlertTrigger.T1_AUTH_FAIL.name(),
                "CRITICAL",
                "同授权码不同 IP 同时心跳，已加入黑名单",
                String.format("{\"authCodeId\":%d,\"clientIp\":\"%s\"}",
                        authCodeId, clientIp)
        );

        log.warn("IP multi-instance confirmed and blacklisted: authCodeId={}, ip={}", authCodeId, clientIp);
    }

    /**
     * 清理过期的 pending 记录（T3 定时任务调用）
     * <p>
     * 删除 created_at < now - 24h 的记录（窗口外自动重置计数）。
     *
     * @return 清理条数
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public int cleanExpiredPending() {
        OffsetDateTime cutoff = TimeUtils.now()
                .minusHours(config.getMultiInstanceWindowHours());

        LambdaQueryWrapper<MultiInstancePending> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(MultiInstancePending::getCreatedAt, cutoff);

        int count = pendingMapper.delete(wrapper);
        if (count > 0) {
            log.info("Cleaned {} expired multi-instance pending records", count);
        }
        return count;
    }
}
