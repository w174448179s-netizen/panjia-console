package com.panjia.console.license.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.panjia.console.common.enums.AlertTrigger;
import com.panjia.console.common.enums.AuthCodeStatus;
import com.panjia.console.license.domain.AuthCode;
import com.panjia.console.license.mapper.AuthCodeMapper;
import com.panjia.console.license.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EXPIRED 状态扫描任务（T1）
 * <p>
 * 每日 01:00 执行，把 end_date < current_date 的 ACTIVE 授权更新为 EXPIRED。
 * <p>
 * ★ 重要原则（§7.1.1 / P1-10）：
 * <ul>
 *   <li>授权是否过期 = 实时判定，不完全依赖定时扫描</li>
 *   <li>即使扫描任务挂了，实时校验（check / activate）仍以 end_date 为准</li>
 *   <li>此字段是"缓存"，权威是 end_date</li>
 * </ul>
 * <p>
 * 实现要点（§7.4 T1）：
 * <ul>
 *   <li>单机内存锁保证同一时刻仅一个实例运行</li>
 *   <li>按 id 游标分批处理，每批 200 条</li>
 *   <li>单行小事务提交，避免大表 UPDATE 长时间持锁</li>
 *   <li>失败跳过本批继续下批，不整体中断</li>
 *   <li>记录 last_run_at / processed_count，超 48h 未跑即告警</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredScanTask {

    private final AuthCodeMapper authCodeMapper;
    private final AlertService alertService;

    /** 批次大小 */
    private static final int BATCH_SIZE = 200;

    /** 单机内存锁（防止并发执行，单机部署无需分布式锁） */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上次运行时间（用于可观测性） */
    private volatile OffsetDateTime lastRunAt;

    /** 上次处理条数 */
    private volatile long lastProcessedCount;

    /** 连续失败天数计数 */
    private final AtomicLong consecutiveFailDays = new AtomicLong(0);

    /**
     * 每日 01:00 执行 EXPIRED 扫描
     */
    @Scheduled(cron = "0 0 1 * * *")
    public void scanExpired() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Expired scan task is already running, skipping this run");
            return;
        }

        long processedCount = 0;
        boolean success = true;
        OffsetDateTime startTime = OffsetDateTime.now();

        try {
            log.info("Expired scan task started at {}", startTime);

            Long lastId = 0L;
            LocalDate today = LocalDate.now();

            while (true) {
                // 按 id 游标分批查询
                LambdaQueryWrapper<AuthCode> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(AuthCode::getStatus, AuthCodeStatus.ACTIVE.name())
                        .lt(AuthCode::getEndDate, today)
                        .gt(AuthCode::getId, lastId)
                        .orderByAsc(AuthCode::getId)
                        .last("LIMIT " + BATCH_SIZE);

                List<AuthCode> batch = authCodeMapper.selectList(wrapper);
                if (batch.isEmpty()) {
                    break;
                }

                // 逐条更新（小事务，避免长时间持锁影响鉴权面）
                for (AuthCode authCode : batch) {
                    try {
                        LambdaUpdateWrapper<AuthCode> updateWrapper = new LambdaUpdateWrapper<>();
                        updateWrapper.eq(AuthCode::getId, authCode.getId())
                                .eq(AuthCode::getStatus, AuthCodeStatus.ACTIVE.name()) // 乐观锁
                                .set(AuthCode::getStatus, AuthCodeStatus.EXPIRED.name())
                                .set(AuthCode::getUpdatedAt, OffsetDateTime.now());

                        int updated = authCodeMapper.update(null, updateWrapper);
                        if (updated > 0) {
                            processedCount++;
                        }
                    } catch (Exception e) {
                        // 单条失败不中断整体
                        log.error("Failed to update expired status for authCodeId={}", authCode.getId(), e);
                    }
                }

                lastId = batch.get(batch.size() - 1).getId();

                // 批次间短暂休眠，减少对 DB 的压力
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            log.info("Expired scan task completed: processed={}, duration={}ms",
                    processedCount,
                    java.time.Duration.between(startTime, OffsetDateTime.now()).toMillis());

        } catch (Exception e) {
            success = false;
            log.error("Expired scan task failed", e);

            // 记录告警
            alertService.createAlert(
                    null,
                    null,
                    "EXPIRED_SCAN_FAILED",
                    AlertTrigger.SERVER_DECISION.name(),
                    "ERROR",
                    "EXPIRED 扫描任务失败",
                    String.format("{\"error\":\"%s\",\"processedCount\":%d}",
                            e.getMessage() != null ? e.getMessage().substring(0, 200) : "unknown",
                            processedCount)
            );

            // 连续失败计数
            long failDays = consecutiveFailDays.incrementAndGet();
            if (failDays >= 3) {
                alertService.createAlert(
                        null,
                        null,
                        "EXPIRED_SCAN_CONSECUTIVE_FAIL",
                        AlertTrigger.T3_INTEGRITY.name(),
                        "CRITICAL",
                        "EXPIRED 扫描连续 3 天失败",
                        String.format("{\"consecutiveFailDays\":%d}", failDays)
                );
            }

        } finally {
            if (success) {
                consecutiveFailDays.set(0);
            }
            lastRunAt = OffsetDateTime.now();
            lastProcessedCount = processedCount;
            running.set(false);
        }
    }

    /**
     * 获取上次运行时间
     */
    public OffsetDateTime getLastRunAt() {
        return lastRunAt;
    }

    /**
     * 获取上次处理条数
     */
    public long getLastProcessedCount() {
        return lastProcessedCount;
    }

    /**
     * 检查任务是否健康（48h 内有运行记录）
     */
    public boolean isHealthy() {
        if (lastRunAt == null) {
            return false;
        }
        return java.time.Duration.between(lastRunAt, OffsetDateTime.now()).toHours() <= 48;
    }
}
