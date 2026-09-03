package com.panjia.console.license.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 创建授权请求 DTO
 * <p>
 * 对应 §4.1 签发授权（运营页面操作）—— V1.2 幂等化
 */
@Data
public class CreateLicenseRequest {

    /** ★ 必填，前端生成 UUID，全局唯一（幂等键） */
    @NotBlank(message = "requestId 不能为空")
    private String requestId;

    /** 客户编号 */
    @NotBlank(message = "客户编号不能为空")
    private String customerNo;

    /** 授权类型：PRODUCTION/TRIAL/TEST */
    @NotBlank(message = "授权类型不能为空")
    private String licenseType;

    /** 版本（套餐） */
    private String version;

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

    /** 签发人（由切面填充，调用方可不传） */
    private String issuedBy;
}
