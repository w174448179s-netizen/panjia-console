package com.panjia.console.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 升级记录实体
 * <p>
 * 对应表：customer.pj_upgrade_record
 */
@Data
@TableName(value = "customer.pj_upgrade_record", autoResultMap = true)
public class UpgradeRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 源版本 */
    private String fromVersion;

    /** 目标版本 */
    private String toVersion;

    /** 状态：PENDING/IN_PROGRESS/SUCCESS/FAILED/ROLLBACK_SUCCESS */
    private String status;

    /** 开始时间 */
    private OffsetDateTime startedAt;

    /** 结束时间 */
    private OffsetDateTime finishedAt;

    /** 错误信息 */
    private String errorMsg;

    /** 操作人 */
    private String operator;

    /** 关联备份 ID */
    private Long backupId;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
