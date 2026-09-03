package com.panjia.console.customer.domain;

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
 * 操作审计日志实体
 * <p>
 * 对应表：customer.pj_ops_log
 * <p>
 * 全系统唯一审计日志表，由 OperationLogAspect 切面统一写入。
 */
@Data
@TableName(value = "customer.pj_ops_log", autoResultMap = true)
public class OpsLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 */
    private String operator;

    /** 操作类型：ISSUE/REVOKE/RESTORE/REBIND/CANCEL_REBINDING/BLACKLIST_REMOVE/... */
    private String action;

    /** 目标类型：AUTH_CODE/CUSTOMER/BLACKLIST/... */
    private String targetType;

    /** 目标 ID */
    private String targetId;

    /** 入参（JSON，脱敏后存储） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String params;

    /** 操作结果：SUCCESS/FAILED */
    private String result;

    /** 操作 IP */
    private String ip;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
