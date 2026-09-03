package com.panjia.console.license.controller.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 激活响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivateResponse {

    /** JWT 令牌 */
    private String jwt;

    /** 离线宽限截止时间 */
    private OffsetDateTime offlineExpireAt;

    /** 客户端模式 */
    private String clientMode;
}
