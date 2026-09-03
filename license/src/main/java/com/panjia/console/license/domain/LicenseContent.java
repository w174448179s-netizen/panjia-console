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
 * License 载荷实体（当前+历史版本）
 * <p>
 * 对应表：auth.t_license_content
 * <p>
 * 每授权码至多 1 条 is_current=TRUE（partial unique index 保证）。
 * license_version 在签发/续期/恢复时 +1，换机不变。
 */
@Data
@TableName(value = "auth.t_license_content", autoResultMap = true)
public class LicenseContent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码 ID */
    private Long authCodeId;

    /** 授权内容版本（签发/续期/恢复时 +1） */
    private Integer licenseVersion;

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

    /** 授权结束日期 */
    private LocalDate endDate;

    /** 维保结束日期 */
    private LocalDate maintenanceEndDate;

    /** 最低支持版本 */
    private String minSupportedVersion;

    /** 最高支持版本 */
    private String maxSupportedVersion;

    /** 签发时使用的密钥版本 */
    private Integer keyVersion;

    /** 签名（预留，当前 JWT 签名在运行时生成） */
    private String signature;

    /** 是否当前版本 */
    private Boolean isCurrent;

    /** 生效时间 */
    private OffsetDateTime effectiveAt;

    /** 过期时间 */
    private OffsetDateTime expiredAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
