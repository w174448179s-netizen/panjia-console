package com.panjia.console.license.controller.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 心跳请求 DTO
 * <p>
 * ★ P0-5 铁律：请求不含 clientMode，服务端始终自行计算。
 * 严禁信任客户端传入的运行模式。
 */
@Data
public class HeartbeatRequest {

    /** 实例 ID */
    @NotBlank(message = "instanceId 不能为空")
    private String instanceId;

    /** 指纹原文 */
    @NotBlank(message = "指纹不能为空")
    private String fingerprint;

    /** 客户端上报时间 */
    private OffsetDateTime reportedAt;

    /** 当前门店数 */
    private Integer currentStores;

    /** 当前用户数 */
    private Integer currentUsers;
}
