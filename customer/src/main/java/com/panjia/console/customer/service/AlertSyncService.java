package com.panjia.console.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.customer.domain.CustomerAlert;
import com.panjia.console.customer.mapper.CustomerAlertMapper;
import com.panjia.console.license.api.LicenseEngine;
import com.panjia.console.license.api.dto.AlertRecordDTO;
import com.panjia.console.license.api.dto.AlertSyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 告警同步服务
 * <p>
 * 从 license 模块以 ID cursor 方式同步告警数据到本地看板，
 * (source, source_id) 唯一约束保证幂等。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertSyncService {

    private final LicenseEngine licenseEngine;
    private final CustomerAlertMapper customerAlertMapper;

    /**
     * 根据 ID 查询告警详情
     *
     * @param id 告警 ID
     * @return 告警信息
     */
    public CustomerAlert getById(Long id) {
        return customerAlertMapper.selectById(id);
    }

    /**
     * 分页查询告警列表
     *
     * @param pageNum    页码
     * @param pageSize   每页大小
     * @param customerNo 客户编号（可选）
     * @param status     状态（可选）
     * @param severity   严重级别（可选）
     * @return 分页结果
     */
    public IPage<CustomerAlert> pageAlerts(int pageNum, int pageSize, String customerNo, String status, String severity) {
        LambdaQueryWrapper<CustomerAlert> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(customerNo)) {
            wrapper.eq(CustomerAlert::getCustomerNo, customerNo);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(CustomerAlert::getStatus, status);
        }
        if (StringUtils.hasText(severity)) {
            wrapper.eq(CustomerAlert::getSeverity, severity);
        }
        wrapper.orderByDesc(CustomerAlert::getCreatedAt);
        return customerAlertMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
    }

    /**
     * 同步告警（从 license 拉取）
     * <p>
     * 以 ID cursor 方式批量拉取，写入本地 pj_alert 表。
     *
     * @param afterId 上次同步的最后一条 ID（从 0 开始）
     * @param limit   本次拉取条数
     * @return 本次同步条数
     */
    public int syncAlerts(long afterId, int limit) {
        AlertSyncResult result = licenseEngine.syncAlerts(afterId, limit);
        if (result.getRecords() == null || result.getRecords().isEmpty()) {
            log.debug("No new alerts to sync, afterId={}", afterId);
            return 0;
        }

        int synced = 0;
        for (AlertRecordDTO dto : result.getRecords()) {
            CustomerAlert alert = new CustomerAlert();
            alert.setSource(dto.getSource());
            alert.setSourceId(dto.getSourceId());
            alert.setCustomerNo(dto.getCustomerNo());
            alert.setAlertType(dto.getAlertType());
            alert.setTrigger(dto.getTrigger());
            alert.setSeverity(dto.getSeverity());
            alert.setTitle(dto.getTitle());
            alert.setDetail(dto.getDetail());
            alert.setStatus(dto.getStatus());
            alert.setOccurredAt(dto.getOccurredAt());
            alert.setCreatedAt(dto.getCreatedAt());

            try {
                customerAlertMapper.insert(alert);
                synced++;
            } catch (DuplicateKeyException e) {
                // (source, source_id) 唯一约束冲突，说明已同步过，跳过
                log.debug("Alert already synced, sourceId={}", dto.getSourceId());
            }
        }

        log.info("Synced alerts: count={}, lastId={}, hasMore={}", synced, result.getLastId(), result.isHasMore());
        return synced;
    }

    /**
     * 确认告警
     *
     * @param id 告警 ID
     */
    public void acknowledgeAlert(Long id) {
        CustomerAlert alert = customerAlertMapper.selectById(id);
        if (alert == null) {
            throw new IllegalArgumentException("Alert not found: id=" + id);
        }
        alert.setStatus("ACKNOWLEDGED");
        customerAlertMapper.updateById(alert);
        log.info("Acknowledged alert: id={}", id);
    }

    /**
     * 关闭告警
     *
     * @param id 告警 ID
     */
    public void closeAlert(Long id) {
        CustomerAlert alert = customerAlertMapper.selectById(id);
        if (alert == null) {
            throw new IllegalArgumentException("Alert not found: id=" + id);
        }
        alert.setStatus("CLOSED");
        customerAlertMapper.updateById(alert);
        log.info("Closed alert: id={}", id);
    }

    /**
     * 获取未处理告警数量
     *
     * @return 未处理告警数
     */
    public long countOpenAlerts() {
        LambdaQueryWrapper<CustomerAlert> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomerAlert::getStatus, "OPEN");
        return customerAlertMapper.selectCount(wrapper);
    }
}
