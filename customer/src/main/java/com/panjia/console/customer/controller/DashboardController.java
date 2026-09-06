package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.mapper.CustomerMapper;
import com.panjia.console.customer.service.AlertSyncService;
import com.panjia.console.license.api.LicenseEngine;
import com.panjia.console.license.api.dto.AppClientView;
import com.panjia.console.license.api.dto.HeartbeatSnapshot;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.mapper.AuthCodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 看板 Controller
 * <p>
 * 运营看板统计数据和运行时概览。
 * 运行时数据直接从心跳流水表查（每个客户最新一条），不做额外快照表。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final LicenseEngine licenseEngine;
    private final CustomerMapper customerMapper;
    private final AuthCodeMapper authCodeMapper;
    private final AlertSyncService alertSyncService;

    /**
     * 获取看板统计概览
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> getStats() {
        long totalCustomers = customerMapper.selectCount(null);

        // 有效授权数（ACTIVE 状态）
        long activeLicenses = authCodeMapper.selectCount(
                new LambdaQueryWrapper<AuthCode>()
                        .eq(AuthCode::getStatus, "ACTIVE"));

        // 从心跳流水表取所有客户最新一条，统计在线状态
        List<HeartbeatSnapshot> all = licenseEngine.pageHeartbeatSnapshots(1, Integer.MAX_VALUE).getRecords();
        long onlineInstances = 0;
        long offlineInstances = 0;
        long lostInstances = 0;
        for (HeartbeatSnapshot s : all) {
            switch (s.getOnlineStatus()) {
                case "ONLINE" -> onlineInstances++;
                case "OFFLINE" -> offlineInstances++;
                case "LOST" -> lostInstances++;
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCustomers", totalCustomers);
        stats.put("activeLicenses", activeLicenses);
        stats.put("onlineInstances", onlineInstances);
        stats.put("offlineInstances", offlineInstances);
        stats.put("lostInstances", lostInstances);
        stats.put("openAlerts", alertSyncService.countOpenAlerts());
        return R.ok(stats);
    }

    /**
     * 分页查询客户运行时列表（直接从心跳流水表取最新）
     */
    @GetMapping("/runtimes")
    public R<IPage<HeartbeatSnapshot>> pageRuntimes(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(licenseEngine.pageHeartbeatSnapshots(pageNum, pageSize));
    }

    /**
     * 根据客户编号查询运行时详情
     */
    @GetMapping("/runtimes/{customerNo}")
    public R<HeartbeatSnapshot> getRuntime(@PathVariable String customerNo) {
        return R.ok(licenseEngine.getHeartbeatSnapshot(customerNo));
    }

    /**
     * 分页查询应用端列表
     * <p>
     * 应用端 = 已注册的指纹绑定（客户端激活成功后生成）。
     * 展示每个应用端的授权码、客户编号、指纹、绑定状态、绑定时间及在线状态。
     */
    @GetMapping("/app-clients")
    public R<IPage<AppClientView>> pageAppClients(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(licenseEngine.pageAppClients(pageNum, pageSize));
    }
}
