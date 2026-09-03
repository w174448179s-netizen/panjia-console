package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 告警记录 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRecordDTO {

    /** 主键 ID（用于 cursor 同步） */
    private Long id;

    /** 告警来源 */
    private String source;

    /** 告警唯一 ID */
    private String sourceId;

    /** 客户编号 */
    private String customerNo;

    /** 告警类型 */
    private String alertType;

    /** 触发原因 */
    private String trigger;

    /** 严重级别 */
    private String severity;

    /** 告警标题 */
    private String title;

    /** 告警详情（JSON 字符串） */
    private String detail;

    /** 状态 */
    private String status;

    /** 发生时间 */
    private OffsetDateTime occurredAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
