package com.panjia.console.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.panjia.console.common.handler.JsonbTypeHandler;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 签发流水实体
 * <p>
 * 对应表：customer.pj_auth_issue_record
 * <p>
 * 投影表，license 是权威源，可整表重建。
 */
@Data
@TableName(value = "customer.pj_auth_issue_record", autoResultMap = true)
public class AuthIssueRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权 ID（跨系统唯一标识） */
    private String licenseId;

    /** 客户编号 */
    private String customerNo;

    /** 授权码 */
    private String authCode;

    /** 授权类型：PRODUCTION/TRIAL/TEST */
    private String licenseType;

    /** 版本（套餐） */
    private String version;

    /** 最大门店数 */
    private Integer maxStores;

    /** 最大用户数 */
    private Integer maxUsers;

    /** 能力位列表（JSON） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String capabilities;

    /** 授权开始日期 */
    private LocalDate startDate;

    /** 授权结束日期 */
    private LocalDate endDate;

    /** 维保结束日期 */
    private LocalDate maintenanceEndDate;

    /** 操作人 */
    private String operator;

    /** 签发时间 */
    private OffsetDateTime issueAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
