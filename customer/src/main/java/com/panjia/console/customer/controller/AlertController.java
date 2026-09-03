package com.panjia.console.customer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.panjia.console.common.annotation.OpsLog;
import com.panjia.console.common.dto.R;
import com.panjia.console.customer.domain.CustomerAlert;
import com.panjia.console.customer.service.AlertSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 告警管理 Controller
 * <p>
 * 管理面接口，负责告警的查询、确认和同步。
 * 告警数据从 license 模块同步到本地看板。
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertSyncService alertSyncService;

    /**
     * 分页查询告警列表
     */
    @GetMapping
    public R<IPage<CustomerAlert>> page(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String customerNo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity) {
        return R.ok(alertSyncService.pageAlerts(pageNum, pageSize, customerNo, status, severity));
    }

    /**
     * 根据 ID 查询告警详情
     */
    @GetMapping("/{id}")
    public R<CustomerAlert> getById(@PathVariable Long id) {
        return R.ok(alertSyncService.getById(id));
    }

    /**
     * 确认告警
     */
    @PostMapping("/{id}/acknowledge")
    @OpsLog(action = "ACKNOWLEDGE_ALERT", targetType = "ALERT")
    public R<Void> acknowledge(@PathVariable Long id) {
        alertSyncService.acknowledgeAlert(id);
        return R.ok();
    }

    /**
     * 关闭告警
     */
    @PostMapping("/{id}/close")
    @OpsLog(action = "CLOSE_ALERT", targetType = "ALERT")
    public R<Void> close(@PathVariable Long id) {
        alertSyncService.closeAlert(id);
        return R.ok();
    }

    /**
     * 手动触发告警同步
     */
    @PostMapping("/sync")
    @OpsLog(action = "SYNC_ALERTS", targetType = "ALERT")
    public R<Integer> sync(@RequestParam(defaultValue = "0") long afterId,
                           @RequestParam(defaultValue = "100") int limit) {
        return R.ok(alertSyncService.syncAlerts(afterId, limit));
    }

    /**
     * 获取未处理告警数量
     */
    @GetMapping("/count/open")
    public R<Long> countOpen() {
        return R.ok(alertSyncService.countOpenAlerts());
    }
}
