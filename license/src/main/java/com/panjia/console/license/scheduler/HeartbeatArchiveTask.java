package com.panjia.console.license.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.console.common.util.TimeUtils;
import com.panjia.console.common.enums.AlertTrigger;
import com.panjia.console.license.domain.HeartbeatRecord;
import com.panjia.console.license.mapper.HeartbeatRecordMapper;
import com.panjia.console.license.security.JwtConfigProperties;
import com.panjia.console.license.service.AlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 心跳归档清理任务（T2）
 * <p>
 * 每日 02:00 执行，将 received_at < now - 180 天的心跳记录
 * 从 t_heartbeat_record 迁移到 t_heartbeat_archive。
 * <p>
 * 实现要点（§7.4 T2）：
 * <ul>
 *   <li>分批 500/事务，避免长事务锁表</li>
 *   <li>主表只保留近 180 天在线明细</li>
 *   <li>归档表用于历史分析/对账，不参与在线查询</li>
 *   <li>归档失败 / 主表超阈值（>100w 行）→ 告警</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatArchiveTask {

    private final HeartbeatRecordMapper heartbeatRecordMapper;
    private final JwtConfigProperties config;
    private final AlertService alertService;

    /** 批次大小 */
    private static final int BATCH_SIZE = 500;

    /** 主表行数告警阈值（100 万） */
    private static final long TABLE_SIZE_WARN_THRESHOLD = 1_000_000;

    /** 单机内存锁 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上次运行时间 */
    private volatile OffsetDateTime lastRunAt;

    /** 上次迁移条数 */
    private volatile long lastMigratedCount;

    /**
     * 每日 02:00 执行心跳归档
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void archiveHeartbeats() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Heartbeat archive task is already running, skipping this run");
            return;
        }

        long migratedCount = 0;
        boolean success = true;
        OffsetDateTime startTime = TimeUtils.now();

        try {
            log.info("Heartbeat archive task started at {}", startTime);

            OffsetDateTime cutoff = TimeUtils.now()
                    .minusDays(config.getHeartbeatRetentionDays());

            while (true) {
                // 查询一批待归档记录
                LambdaQueryWrapper<HeartbeatRecord> wrapper = new LambdaQueryWrapper<>();
                wrapper.lt(HeartbeatRecord::getReceivedAt, cutoff)
                        .orderByAsc(HeartbeatRecord::getId)
                        .last("LIMIT " + BATCH_SIZE);

                List<HeartbeatRecord> batch = heartbeatRecordMapper.selectList(wrapper);
                if (batch.isEmpty()) {
                    break;
                }

                // 本批次迁移（INSERT 归档 + DELETE 主表，同一事务）
                try {
                    migrateBatch(batch);
                    migratedCount += batch.size();
                } catch (Exception e) {
                    log.error("Failed to migrate heartbeat batch, size={}", batch.size(), e);
                    // 失败跳过本批，继续下一批（避免整体中断）
                    // 但记录告警
                    alertService.createAlert(
                            null,
                            null,
                            "HEARTBEAT_ARCHIVE_BATCH_FAIL",
                            AlertTrigger.SERVER_DECISION.name(),
                            "WARN",
                            "心跳归档批次失败",
                            String.format("{\"batchSize\":%d,\"error\":\"%s\"}",
                                    batch.size(),
                                    e.getMessage() != null ? e.getMessage().substring(0, 200) : "unknown")
                    );
                }

                // 批次间休眠
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 检查主表行数是否超过阈值
            long totalCount = heartbeatRecordMapper.selectCount(
                    new LambdaQueryWrapper<HeartbeatRecord>()
            ).longValue();
            if (totalCount > TABLE_SIZE_WARN_THRESHOLD) {
                alertService.createAlert(
                        null,
                        null,
                        "HEARTBEAT_TABLE_SIZE_WARN",
                        AlertTrigger.T3_INTEGRITY.name(),
                        "WARN",
                        "心跳记录表体积告警",
                        String.format("{\"totalCount\":%d,\"threshold\":%d}",
                                totalCount, TABLE_SIZE_WARN_THRESHOLD)
                );
            }

            log.info("Heartbeat archive task completed: migrated={}, totalCount={}, duration={}ms",
                    migratedCount, totalCount,
                    java.time.Duration.between(startTime, TimeUtils.now()).toMillis());

        } catch (Exception e) {
            success = false;
            log.error("Heartbeat archive task failed", e);
            alertService.createAlert(
                    null,
                    null,
                    "HEARTBEAT_ARCHIVE_TASK_FAILED",
                    AlertTrigger.SERVER_DECISION.name(),
                    "ERROR",
                    "心跳归档任务失败",
                    String.format("{\"error\":\"%s\",\"migratedCount\":%d}",
                            e.getMessage() != null ? e.getMessage().substring(0, 200) : "unknown",
                            migratedCount)
            );
        } finally {
            lastRunAt = TimeUtils.now();
            lastMigratedCount = migratedCount;
            running.set(false);
        }
    }

    /**
     * 迁移一个批次（INSERT 归档 + DELETE 主表，同一事务）
     */
    @Transactional(rollbackFor = Exception.class)
    protected void migrateBatch(List<HeartbeatRecord> batch) {
        List<Long> ids = batch.stream()
                .map(HeartbeatRecord::getId)
                .collect(Collectors.toList());

        // INSERT INTO t_heartbeat_archive
        // 使用原生 SQL 批量插入（MyBatis-Plus 没有直接的批量插入到另一张表的方法）
        // 这里通过 Mapper 自定义 SQL 实现
        batch.forEach(record -> {
            heartbeatRecordMapper.insertArchive(record);
        });

        // DELETE FROM t_heartbeat_record WHERE id IN (...)
        heartbeatRecordMapper.deleteBatchIds(ids);
    }

    /**
     * 获取上次运行时间
     */
    public OffsetDateTime getLastRunAt() {
        return lastRunAt;
    }

    /**
     * 获取上次迁移条数
     */
    public long getLastMigratedCount() {
        return lastMigratedCount;
    }
}
