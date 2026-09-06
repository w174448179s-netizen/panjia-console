package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 应用端视图 DTO
 * <p>
 * 展示已注册的应用端（指纹绑定）信息，联表 t_fingerprint_binding + t_auth_code + 最新心跳。
 * <p>
 * 应用端注册 = 客户端用授权码激活（activate）后生成指纹绑定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppClientView {

    /** 授权码 */
    private String authCode;

    /** 客户编号 */
    private String customerNo;

    /** 指纹 SHA-256 哈希 */
    private String fpHash;

    /** 原始设备指纹（激活时上报） */
    private String fingerprint;

    /** 客户端产品版本（激活时上报） */
    private String productVersion;

    /** 绑定状态：ACTIVE/INVALIDATED */
    private String status;

    /** 绑定时间 */
    private OffsetDateTime boundAt;

    /** 失效时间 */
    private OffsetDateTime invalidatedAt;

    /** 失效原因 */
    private String invalidateReason;

    /** 实例 ID（来自最新心跳） */
    private String instanceId;

    /** 最后心跳时间（服务端接收时间） */
    private OffsetDateTime lastHeartbeatAt;

    /** 在线状态：ONLINE/OFFLINE/LOST/UNKNOWN */
    private String onlineStatus;

    /** 客户端模式（来自最新心跳） */
    private String clientMode;

    /** 受限原因（LicenseErrorCode name），NORMAL 时为 NULL */
    private String restrictReason;
}
