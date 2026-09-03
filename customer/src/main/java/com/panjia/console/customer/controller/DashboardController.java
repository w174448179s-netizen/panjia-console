package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.CustomerRuntime;
import com.panjia.console.customer.service.AlertSyncService;
import com.panjia.console.customer.service.RuntimeMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 看板 Controller
 * <p>
 * 管理面接口，提供运营看板所需的统计数据和运行时概览。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final RuntimeMonitorService runtimeMonitorService;
    private final AlertSyncService alertSyncService;

    /**
     * 获取看板统计概览
     * <p>
     * 返回客户总数、在线/离线数、受限数、未处理告警数等核心指标。
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> getStats() {
        Map<String, Object> stats = runtimeMonitorService.getDashboardStats();
        stats.put("openAlertCount", alertSyncService.countOpenAlerts());
        return R.ok(stats);
    }

    /**
     * 分页查询客户运行时列表
     */
    @GetMapping("/runtimes")
    public R<IPage<CustomerRuntime>> pageRuntimes(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String onlineStatus) {
        return R.ok(runtimeMonitorService.pageRuntimes(pageNum, pageSize, onlineStatus));
    }

    /**
     * 根据客户编号查询运行时详情
     */
    @GetMapping("/runtimes/{customerNo}")
    public R<CustomerRuntime> getRuntime(@PathVariable String customerNo) {
        return R.ok(runtimeMonitorService.getByCustomerNo(customerNo));
    }

    /**
     * 手动刷新指定客户的运行时快照
     */
    @PostMapping("/runtimes/{customerNo}/refresh")
    public R<Void> refreshRuntime(@PathVariable String customerNo) {
        runtimeMonitorService.refreshRuntime(customerNo);
        return R.ok();
    }

    /**
     * 手动刷新全部运行时快照
     */
    @PostMapping("/runtimes/refresh-all")
    public R<Integer> refreshAll() {
        return R.ok(runtimeMonitorService.refreshAllRuntimes());
    }
}
