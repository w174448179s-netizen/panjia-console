package com.panjia.console.license.domain;

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
 * 授权码实体（授权状态权威）
 * <p>
 * 对应表：auth.t_auth_code
 * <p>
 * 状态机：ACTIVE / REBINDING / EXPIRED / REVOKED（四值，无临时态）
 */
@Data
@TableName(value = "auth.t_auth_code", autoResultMap = true)
public class AuthCode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码（PJ-XXXX-XXXX） */
    private String authCode;

    /** 客户编号 */
    private String customerNo;

    /** 授权类型：PRODUCTION/TRIAL/TEST */
    private String licenseType;

    /** 版本（套餐） */
    private String version;

    /** 最大门店数 */
    private Integer maxStores;

    /** 最大用户数 */
    private Integer maxUsers;

    /** 能力位（JSON 数组） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String capabilities;

    /** 授权开始日期 */
    private LocalDate startDate;

    /** 授权结束日期（商业授权到期权威） */
    private LocalDate endDate;

    /** 维保结束日期 */
    private LocalDate maintenanceEndDate;

    /** 最低支持版本 */
    private String minSupportedVersion;

    /** 最高支持版本 */
    private String maxSupportedVersion;

    /** 离线宽限截止时间 */
    private OffsetDateTime offlineExpireAt;

    /** 状态：ACTIVE/REBINDING/EXPIRED/REVOKED */
    private String status;

    /** 是否测试授权 */
    private Boolean isTest;

    /** 签发幂等键（前端生成 UUID） */
    private String requestId;

    /** 签发人 */
    private String issuedBy;

    /** 签发时间 */
    private OffsetDateTime issuedAt;

    /** 吊销时间 */
    private OffsetDateTime revokedAt;

    /** 吊销原因 */
    private String revokedReason;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;

    // ---- 以下为 transient 字段，用于业务逻辑传递 ----

    /** 当前 license_version（联表查询时填充） */
    @TableField(exist = false)
    private Integer currentLicenseVersion;
}
