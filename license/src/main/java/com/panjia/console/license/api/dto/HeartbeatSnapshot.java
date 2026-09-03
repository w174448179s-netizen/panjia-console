package com.panjia.console.license.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 心跳快照 DTO
 * <p>
 * customer 模块通过 getHeartbeatSnapshot 获取指定客户的最新心跳数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatSnapshot {

    /** 客户编号 */
    private String customerNo;

    /** 实例 ID */
    private String instanceId;

    /** 最后心跳时间（服务端接收时间） */
    private OffsetDateTime lastHeartbeatAt;

    /** 在线状态：ONLINE/OFFLINE/LOST */
    private String onlineStatus;

    /** 当前门店数 */
    private Integer currentStores;

    /** 当前用户数 */
    private Integer currentUsers;

    /** 客户端模式 */
    private String clientMode;
}
