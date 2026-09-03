package com.panjia.console.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 备份记录实体
 * <p>
 * 对应表：customer.pj_backup_record
 */
@Data
@TableName(value = "customer.pj_backup_record", autoResultMap = true)
public class BackupRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文件路径 */
    private String filePath;

    /** 文件名 */
    private String fileName;

    /** 文件大小（字节） */
    private Long fileSize;

    /** SHA256 校验值 */
    private String sha256;

    /** 备份类型：FULL/INCREMENTAL */
    private String backupType;

    /** 状态：PENDING/IN_PROGRESS/SUCCESS/FAILED */
    private String status;

    /** 开始时间 */
    private OffsetDateTime startedAt;

    /** 结束时间 */
    private OffsetDateTime finishedAt;

    /** 错误信息 */
    private String errorMsg;

    /** 操作人 */
    private String operator;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
