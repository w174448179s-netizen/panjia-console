package com.panjia.console.license.controller.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 激活请求 DTO
 */
@Data
public class ActivateRequest {

    /** 授权码 */
    @NotBlank(message = "授权码不能为空")
    private String authCode;

    /** 指纹原文 */
    @NotBlank(message = "指纹不能为空")
    private String fingerprint;

    /** 产品版本 */
    private String productVersion;

    /** 公司名称（可选） */
    private String company;

    /** 实例 ID（可选） */
    private String instanceId;
}
