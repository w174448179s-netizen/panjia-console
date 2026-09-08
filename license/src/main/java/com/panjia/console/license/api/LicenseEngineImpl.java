package com.panjia.console.license.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.common.util.TimeUtils;
import com.panjia.console.common.enums.AuthCodeStatus;
import com.panjia.console.license.api.dto.*;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.domain.HeartbeatRecord;
import com.panjia.console.license.mapper.AuthCodeMapper;
import com.panjia.console.license.mapper.FingerprintBindingMapper;
import com.panjia.console.license.mapper.HeartbeatRecordMapper;
import com.panjia.console.license.service.AlertService;
import com.panjia.console.license.service.BlacklistService;
import com.panjia.console.license.service.LicenseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * LicenseEngine 实现类
 * <p>
 * customer 模块通过此接口调用 license 能力（同进程方法调用）。
 * 将来若拆成两个服务，把此处换成 HTTP 客户端调用即可，业务逻辑不改。
 * <p>
 * ★ 包边界铁律：customer 只通过此接口调用 license，
 * 禁止直接 import license 的 Service/Mapper/实体。Code Review 必查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LicenseEngineImpl implements LicenseEngine {

    private final LicenseService licenseService;
    private final BlacklistService blacklistService;
    private final AlertService alertService;
    private final AuthCodeMapper authCodeMapper;
    private final HeartbeatRecordMapper heartbeatRecordMapper;
    private final FingerprintBindingMapper fingerprintBindingMapper;

    @Override
    public CreateLicenseResult createLicense(CreateLicenseRequest req) {
        return licenseService.createLicense(req);
    }

    @Override
    public void revoke(String authCode, String reason) {
        licenseService.revoke(authCode, reason);
    }

    @Override
    public RestoreResult restore(String authCode, String reason) {
        return licenseService.restore(authCode, reason);
    }

    @Override
    public void renew(RenewLicenseRequest req) {
        licenseService.renew(req);
    }

    @Override
    public void invalidateFingerprint(String authCode) {
        licenseService.invalidateFingerprint(authCode);
    }

    @Override
    public void cancelRebinding(String authCode, String reason) {
        licenseService.cancelRebinding(authCode, reason);
    }

    @Override
    public void removeFromBlacklist(String authCode) {
        blacklistService.removeFromBlacklist(authCode);
    }

    @Override
    public void expireTestCode(String testCode) {
        // V1 简化：测试码功能按需扩展，此处预留接口
        log.info("expireTestCode called (V1 simplified): testCode={}", testCode);
        // TODO: 实现测试码失效逻辑（如需要）
    }

    @Override
    public AlertSyncResult syncAlerts(long afterId, int limit) {
        return alertService.syncAlerts(afterId, limit);
    }

    @Override
    public HeartbeatSnapshot getHeartbeatSnapshot(String customerNo) {
        // 查询该客户最近的心跳记录
        LambdaQueryWrapper<HeartbeatRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HeartbeatRecord::getCustomerNo, customerNo)
                .orderByDesc(HeartbeatRecord::getReceivedAt)
                .last("LIMIT 1");

        HeartbeatRecord latest = heartbeatRecordMapper.selectOne(wrapper);

        if (latest == null) {
            return HeartbeatSnapshot.builder()
                    .customerNo(customerNo)
                    .onlineStatus("UNKNOWN")
                    .build();
        }

        // 计算在线状态（以 received_at 为权威）
        String onlineStatus = calculateOnlineStatus(latest.getReceivedAt());

        return HeartbeatSnapshot.builder()
                .customerNo(customerNo)
                .instanceId(latest.getInstanceId())
                .lastHeartbeatAt(latest.getReceivedAt())
                .onlineStatus(onlineStatus)
                .currentStores(latest.getCurrentStores())
                .currentUsers(latest.getCurrentUsers())
                .clientMode(latest.getClientMode())
                .build();
    }

    @Override
    public BlacklistView getBlacklist(String customerNo) {
        return blacklistService.getBlacklistByCustomer(customerNo);
    }

    @Override
    public IPage<HeartbeatSnapshot> pageHeartbeatSnapshots(int pageNum, int pageSize) {
        IPage<HeartbeatRecord> recordPage = heartbeatRecordMapper.selectLatestPerCustomer(
                new Page<>(pageNum, pageSize));

        return recordPage.convert(record -> HeartbeatSnapshot.builder()
                .customerNo(record.getCustomerNo())
                .instanceId(record.getInstanceId())
                .lastHeartbeatAt(record.getReceivedAt())
                .onlineStatus(calculateOnlineStatus(record.getReceivedAt()))
                .currentStores(record.getCurrentStores())
                .currentUsers(record.getCurrentUsers())
                .clientMode(record.getClientMode())
                .build());
    }

    @Override
    public IPage<AppClientView> pageAppClients(int pageNum, int pageSize) {
        IPage<AppClientView> page = fingerprintBindingMapper.pageAppClients(new Page<>(pageNum, pageSize));
        // 计算在线状态
        page.getRecords().forEach(view ->
                view.setOnlineStatus(calculateOnlineStatus(view.getLastHeartbeatAt())));
        return page;
    }

    /**
     * 根据最后心跳时间计算在线状态
     * <p>
     * 规则（§4.9）：
     * - now - last_received_at <= 26h → ONLINE
     * - 26h < ... <= 7d → OFFLINE
     * - > 7d → LOST
     * （26h = 24h 上报周期 + 2h 缓冲）
     */
    private String calculateOnlineStatus(OffsetDateTime lastReceivedAt) {
        if (lastReceivedAt == null) {
            return "UNKNOWN";
        }
        OffsetDateTime now = TimeUtils.now();
        long hoursSinceLast = java.time.Duration.between(lastReceivedAt, now).toHours();

        if (hoursSinceLast <= 26) {
            return "ONLINE";
        } else if (hoursSinceLast <= 7 * 24) {
            return "OFFLINE";
        } else {
            return "LOST";
        }
    }
}
