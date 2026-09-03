package com.panjia.console.customer.controller;

import com.panjia.console.common.dto.R;
import com.panjia.console.customer.service.RuntimeMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 心跳 Controller
 * <p>
 * 客户端心跳上报接口，用于更新客户在线状态和运行时快照。
 * 此接口为轻量级回调，不做复杂业务处理。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/heartbeat")
@RequiredArgsConstructor
public class HeartbeatController {

    private final RuntimeMonitorService runtimeMonitorService;

    /**
     * 心跳上报
     * <p>
     * 客户端定期上报心跳，服务端更新最后心跳时间和在线状态。
     *
     * @param customerNo 客户编号
     * @param instanceId 实例 ID
     */
    @PostMapping("/report")
    public R<Void> report(@RequestParam String customerNo,
                          @RequestParam String instanceId) {
        log.debug("Heartbeat received: customerNo={}, instanceId={}", customerNo, instanceId);
        runtimeMonitorService.onHeartbeat(customerNo, instanceId);
        return R.ok();
    }

    /**
     * 心跳探活
     * <p>
     * 简单的探活接口，客户端用于确认服务端可用。
     */
    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("pong");
    }
}
