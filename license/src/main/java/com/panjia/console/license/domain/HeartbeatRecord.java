package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.panjia.console.common.handler.JsonbTypeHandler;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 心跳记录实体
 * <p>
 * 对应表：auth.t_heartbeat_record
 * <p>
 * ★ 在线判定以 received_at（服务端接收时间）为权威，
 * reported_at（客户端上报时间）仅用于诊断客户端时钟问题。
 */
@Data
@TableName(value = "auth.t_heartbeat_record", autoResultMap = true)
public class HeartbeatRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码 ID */
    private Long authCodeId;

    /** 客户编号 */
    private String customerNo;

    /** 指纹哈希 */
    private String fpHash;

    /** 实例 ID（客户端 /data/.panjia_instance_id） */
    private String instanceId;

    /** 客户端上报时间（仅诊断用） */
    private OffsetDateTime reportedAt;

    /** 服务端接收时间（在线判定权威） */
    private OffsetDateTime receivedAt;

    /** 当前门店数 */
    private Integer currentStores;

    /** 当前用户数 */
    private Integer currentUsers;

    /** 客户端模式（服务端计算后记录） */
    private String clientMode;

    /** 原始请求体（JSON，用于诊断） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String raw;
}
