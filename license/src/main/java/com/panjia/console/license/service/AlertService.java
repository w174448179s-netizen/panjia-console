package com.panjia.console.license.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.console.license.api.dto.AlertRecordDTO;
import com.panjia.console.license.api.dto.AlertSyncResult;
import com.panjia.console.license.domain.AlertRecord;
import com.panjia.console.license.mapper.AlertRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 告警服务
 * <p>
 * 负责告警记录的写入和同步查询。
 * customer 模块通过 syncAlerts(afterId, limit) 以 ID cursor 拉取告警到看板。
 * (source, source_id) 唯一约束保证幂等同步。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    private final AlertRecordMapper alertRecordMapper;

    /**
     * 创建告警记录
     * <p>
     * 自动生成 sourceId（UUID），保证幂等。
     *
     * @param customerNo 客户编号
     * @param authCodeId 授权码 ID
     * @param alertType  告警类型
     * @param trigger    触发原因
     * @param severity   严重级别
     * @param title      标题
     * @param detail     详情（JSON 字符串）
     */
    public void createAlert(String customerNo, Long authCodeId, String alertType,
                            String trigger, String severity, String title, String detail) {
        AlertRecord record = new AlertRecord();
        record.setSource("LICENSE_SERVER");
        record.setSourceId(UUID.randomUUID().toString());
        record.setCustomerNo(customerNo);
        record.setAuthCodeId(authCodeId);
        record.setAlertType(alertType);
        record.setTrigger(trigger);
        record.setSeverity(severity);
        record.setTitle(title);
        record.setDetail(detail);
        record.setStatus("OPEN");
        record.setOccurredAt(OffsetDateTime.now());

        try {
            alertRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // (source, source_id) 冲突，理论上 UUID 不会冲突，做个兜底
            log.warn("Duplicate alert source_id: {}", record.getSourceId());
        }
    }

    /**
     * 同步告警（ID cursor 分页）
     * <p>
     * customer 模块调用此方法从 auth 拉取告警数据到本地看板。
     * 按 id 升序拉取，保证不会重复也不会遗漏。
     *
     * @param afterId 上次同步的最后一条 ID（从 0 开始）
     * @param limit   本次拉取条数
     * @return 同步结果
     */
    public AlertSyncResult syncAlerts(long afterId, int limit) {
        LambdaQueryWrapper<AlertRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.gt(AlertRecord::getId, afterId)
                .orderByAsc(AlertRecord::getId)
                .last("LIMIT " + Math.min(limit + 1, 1000)); // 多取一条判断 hasMore

        List<AlertRecord> records = alertRecordMapper.selectList(wrapper);

        boolean hasMore = records.size() > limit;
        List<AlertRecord> resultList = hasMore ? records.subList(0, limit) : records;

        List<AlertRecordDTO> dtos = resultList.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        Long lastId = dtos.isEmpty() ? afterId : dtos.get(dtos.size() - 1).getId();

        return AlertSyncResult.builder()
                .records(dtos)
                .lastId(lastId)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 转换为 DTO
     */
    private AlertRecordDTO toDTO(AlertRecord record) {
        return AlertRecordDTO.builder()
                .id(record.getId())
                .source(record.getSource())
                .sourceId(record.getSourceId())
                .customerNo(record.getCustomerNo())
                .alertType(record.getAlertType())
                .trigger(record.getTrigger())
                .severity(record.getSeverity())
                .title(record.getTitle())
                .detail(record.getDetail())
                .status(record.getStatus())
                .occurredAt(record.getOccurredAt())
                .createdAt(record.getCreatedAt())
                .build();
    }
}
