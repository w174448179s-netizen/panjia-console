package com.panjia.console.license.scheduler;

import com.panjia.console.common.util.TimeUtils;
import com.panjia.console.common.enums.AlertTrigger;
import com.panjia.console.license.service.AlertService;
import com.panjia.console.license.service.MultiInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多实例 pending 超时清理任务（T3）
 * <p>
 * 每小时执行，删除 created_at < now - 24h 的 t_multi_instance_pending 记录。
 * <p>
 * 必要性（§7.4 T3 / H3）：
 * 客户短暂异常产生的 pending 记录若不清，长期累积会让 confirm_count 永久偏高，
 * 未来可能误触发拉黑。24h 窗口与多实例检测语义一致，清理即"重置计数"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiInstancePendingCleanTask {

    private final MultiInstanceService multiInstanceService;
    private final AlertService alertService;

    /** 单机内存锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上次运行时间 */
    private volatile OffsetDateTime lastRunAt;

    /** 上次清理条数 */
    private volatile int lastCleanedCount;

    /**
     * 每小时执行清理
     */
    @Scheduled(cron = "0 0 * * * *")
    public void cleanExpiredPending() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Multi-instance pending clean task is already running, skipping");
            return;
        }

        try {
            int cleaned = multiInstanceService.cleanExpiredPending();
            lastCleanedCount = cleaned;

            if (cleaned > 0) {
                log.info("Multi-instance pending clean task: cleaned {} records", cleaned);
            }

        } catch (Exception e) {
            log.error("Multi-instance pending clean task failed", e);
            alertService.createAlert(
                    null,
                    null,
                    "PENDING_CLEAN_TASK_FAILED",
                    AlertTrigger.SERVER_DECISION.name(),
                    "WARN",
                    "多实例 pending 清理任务失败",
                    String.format("{\"error\":\"%s\"}",
                            e.getMessage() != null ? e.getMessage().substring(0, 200) : "unknown")
            );
        } finally {
            lastRunAt = TimeUtils.now();
            running.set(false);
        }
    }

    public OffsetDateTime getLastRunAt() {
        return lastRunAt;
    }

    public int getLastCleanedCount() {
        return lastCleanedCount;
    }
}
