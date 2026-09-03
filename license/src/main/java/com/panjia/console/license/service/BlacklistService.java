package com.panjia.console.license.service;

import com.panjia.console.common.enums.AuthCodeStatus;
import com.panjia.console.common.enums.BlacklistReason;
import com.panjia.console.common.exception.LicenseErrorCode;
import com.panjia.console.common.exception.LicenseException;
import com.panjia.console.license.api.dto.BlacklistView;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.domain.Blacklist;
import com.panjia.console.license.mapper.AuthCodeMapper;
import com.panjia.console.license.mapper.BlacklistMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 黑名单服务
 * <p>
 * 黑名单 reason 规则（§7.3）：
 * <ul>
 *   <li>MULTI_INSTANCE / MANUAL → removeFromBlacklist 可移除</li>
 *   <li>REVOKE → 不可移除，必须 restore 恢复授权</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistService {

    private final BlacklistMapper blacklistMapper;
    private final AuthCodeMapper authCodeMapper;
    private final AlertService alertService;

    /**
     * 检查授权是否在黑名单中
     *
     * @param authCodeId 授权码 ID
     * @return true = 在黑名单中
     */
    public boolean isBlacklisted(Long authCodeId) {
        return blacklistMapper.selectByAuthCodeId(authCodeId) != null;
    }

    /**
     * 获取黑名单记录
     *
     * @param authCodeId 授权码 ID
     * @return 黑名单实体（不存在返回 null）
     */
    public Blacklist getBlacklist(Long authCodeId) {
        return blacklistMapper.selectByAuthCodeId(authCodeId);
    }

    /**
     * 从黑名单移除
     * <p>
     * 仅 MULTI_INSTANCE / MANUAL 可移除；
     * REVOKE 原因的黑名单不可通过此方法移除，必须走 restore 流程。
     *
     * @param authCode 授权码
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void removeFromBlacklist(String authCode) {
        AuthCode authCodeEntity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
        if (authCodeEntity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        Blacklist blacklist = blacklistMapper.selectByAuthCodeId(authCodeEntity.getId());
        if (blacklist == null) {
            // 不在黑名单中，幂等返回
            log.warn("AuthCode not in blacklist: {}", authCode);
            return;
        }

        // REVOKE 原因的黑名单不可直接移除
        if (BlacklistReason.REVOKE.name().equals(blacklist.getReason())) {
            throw new IllegalStateException("REVOKE 原因的黑名单不可直接移除，请使用恢复授权操作");
        }

        // 移除黑名单
        blacklistMapper.deleteById(blacklist.getId());

        // 如果授权是 REVOKED 状态但不是 REVOKE 原因（异常数据），保持状态不变
        // 正常情况下 MULTI_INSTANCE/MANUAL 拉黑不会改变 auth_code.status

        alertService.createAlert(
                authCodeEntity.getCustomerNo(),
                authCodeEntity.getId(),
                "BLACKLIST_REMOVED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "INFO",
                "已从黑名单移除",
                String.format("{\"authCode\":\"%s\",\"reason\":\"%s\"}",
                        com.panjia.console.common.util.AuthCodeGenerator.mask(authCode),
                        blacklist.getReason())
        );

        log.info("Removed from blacklist: authCode={}, reason={}", authCode, blacklist.getReason());
    }

    /**
     * 获取指定客户的黑名单视图
     *
     * @param customerNo 客户编号
     * @return 黑名单视图（不在黑名单中返回 null）
     */
    public BlacklistView getBlacklistByCustomer(String customerNo) {
        // 先查客户的授权码
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AuthCode> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(AuthCode::getCustomerNo, customerNo)
                .last("LIMIT 1");
        AuthCode authCode = authCodeMapper.selectOne(wrapper);
        if (authCode == null) {
            return null;
        }

        Blacklist blacklist = blacklistMapper.selectByAuthCodeId(authCode.getId());
        if (blacklist == null) {
            return null;
        }

        return BlacklistView.builder()
                .authCode(authCode.getAuthCode())
                .customerNo(customerNo)
                .reason(blacklist.getReason())
                .createdAt(blacklist.getCreatedAt())
                .build();
    }

    /**
     * 手动加入黑名单
     *
     * @param authCode 授权码
     * @param reason   原因（MANUAL）
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void addToBlacklist(String authCode, String reason) {
        AuthCode entity = authCodeMapper.selectByAuthCodeForUpdate(authCode);
        if (entity == null) {
            throw new LicenseException(LicenseErrorCode.AUTH_CODE_NOT_FOUND);
        }

        // 检查是否已在黑名单
        if (isBlacklisted(entity.getId())) {
            log.warn("AuthCode already in blacklist: {}", authCode);
            return;
        }

        Blacklist blacklist = new Blacklist();
        blacklist.setAuthCodeId(entity.getId());
        blacklist.setReason(BlacklistReason.MANUAL.name());
        blacklist.setCreatedBy("system");
        blacklistMapper.insert(blacklist);

        alertService.createAlert(
                entity.getCustomerNo(),
                entity.getId(),
                "BLACKLIST_ADDED",
                com.panjia.console.common.enums.AlertTrigger.SERVER_DECISION.name(),
                "WARN",
                "已加入黑名单",
                String.format("{\"authCode\":\"%s\",\"reason\":\"%s\"}",
                        com.panjia.console.common.util.AuthCodeGenerator.mask(authCode), reason)
        );

        log.info("Added to blacklist: authCode={}, reason={}", authCode, reason);
    }
}
