package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建授权结果 DTO
 * <p>
 * 同一 requestId 重复调用必须返回同一结果（幂等）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLicenseResult {

    /** 授权码（PJ-XXXX-XXXX） */
    private String authCode;

    /** 跨系统唯一标识（签发流水用） */
    private String licenseId;

    /** 授权内容版本（首次签发 = 1） */
    private Integer licenseVersion;
}
