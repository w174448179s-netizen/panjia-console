package com.panjia.console.license.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * JWT Claims DTO
 * <p>
 * 对应 §5.5 JWT Claims（RS256）。
 * 所有字段与客户端 V1.1 对齐。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LicenseJwtClaims {

    /** 授权码 */
    private String authCode;

    /** 客户编号 */
    private String customerNo;

    /** 公司名称 */
    private String company;

    /** 套餐版本 */
    private String plan;

    /** 指纹哈希 */
    private String fpHash;

    /** 最大门店数 */
    private Integer maxStores;

    /** 最大用户数 */
    private Integer maxUsers;

    /** 能力位列表 */
    private List<String> capabilities;

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

    /** 密钥版本 */
    private Integer keyVersion;

    /** 授权内容版本 */
    private Integer licenseVersion;

    /** 客户端模式 */
    private String clientMode;

    /** 离线宽限截止时间 */
    private OffsetDateTime offlineExpireAt;

    /** 签发时间（iat） */
    private OffsetDateTime issuedAt;

    /** 过期时间（exp） */
    private OffsetDateTime expiresAt;
}
