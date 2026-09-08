package com.panjia.console.license.controller.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 心跳响应 DTO
 * <p>
 * ★ P0-B：心跳会在 token 剩余寿命低于续签阈值时自动重签并下发新 JWT（token 字段），
 * 客户端验签后无感替换；其余时间 JWT 只在 activate 时签发。
 * clientMode 由服务端计算返回，客户端只执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatResponse {

    /** 离线宽限截止时间（now + 7d） */
    private OffsetDateTime offlineExpireAt;

    /** 客户端模式（NORMAL/RESTRICT） */
    private String clientMode;

    /** 受限原因错误码（仅受限模式时有值） */
    private String code;

    /** 续签后的新 JWT（token 剩余寿命低于阈值时才有值，客户端无感替换） */
    private String token;
}
