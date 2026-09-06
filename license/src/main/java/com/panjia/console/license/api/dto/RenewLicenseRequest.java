package com.panjia.console.license.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 续期授权请求 DTO
 * <p>
 * 续期不新增 license_version，旧 JWT 不失效。
 * 仅更新 t_auth_code 与当前 t_license_content 的授权参数，
 * 客户端下次 check 时自动获取最新配额。
 */
@Data
public class RenewLicenseRequest {

    /** 授权码 */
    @NotBlank(message = "授权码不能为空")
    private String authCode;

    /** 新的到期日期（必填，续期核心） */
    private LocalDate endDate;

    /** 新的版本（套餐），不传则保持不变 */
    private String version;

    /** 新的最大门店数，不传则保持不变 */
    private Integer maxStores;

    /** 新的最大用户数，不传则保持不变 */
    private Integer maxUsers;

    /** 新的能力位列表，不传则保持不变 */
    private List<String> capabilities;

    /** 新的维保结束日期，不传则保持不变 */
    private LocalDate maintenanceEndDate;

    /** 续期原因 */
    private String reason;
}
