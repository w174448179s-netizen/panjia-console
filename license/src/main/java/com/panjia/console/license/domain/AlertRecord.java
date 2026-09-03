package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.panjia.console.common.handler.JsonbTypeHandler;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 告警记录实体
 * <p>
 * 对应表：auth.t_alert_record
 * <p>
 * 由 license 模块写入，customer 模块通过 syncAlerts 同步到看板。
 * (source, source_id) 唯一约束保证幂等同步。
 */
@Data
@TableName(value = "auth.t_alert_record", autoResultMap = true)
public class AlertRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 告警来源 */
    private String source;

    /** 告警唯一 ID（UUID） */
    private String sourceId;

    /** 客户编号 */
    private String customerNo;

    /** 授权码 ID */
    private Long authCodeId;

    /** 告警类型 */
    private String alertType;

    /** 触发原因：T1_AUTH_FAIL/T2_SERVER_REVOKED/T3_INTEGRITY/SERVER_DECISION */
    private String trigger;

    /** 严重级别：INFO/WARN/ERROR/CRITICAL */
    private String severity;

    /** 告警标题 */
    private String title;

    /** 告警详情（JSON） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detail;

    /** 状态：OPEN/ACKNOWLEDGED/CLOSED */
    private String status;

    /** 发生时间 */
    private OffsetDateTime occurredAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
