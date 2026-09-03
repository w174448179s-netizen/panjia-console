package com.panjia.console.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 客户运行时快照实体
 * <p>
 * 对应表：customer.pj_customer_runtime
 * <p>
 * 看板数据，从心跳聚合刷新。
 */
@Data
@TableName(value = "customer.pj_customer_runtime", autoResultMap = true)
public class CustomerRuntime implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户编号 */
    private String customerNo;

    /** 实例 ID */
    private String instanceId;

    /** 最后心跳时间 */
    private OffsetDateTime lastHeartbeatAt;

    /** 在线状态：ONLINE/OFFLINE/LOST/UNKNOWN */
    private String onlineStatus;

    /** 当前门店数 */
    private Integer currentStores;

    /** 当前用户数 */
    private Integer currentUsers;

    /** 客户端模式：NORMAL/RESTRICT */
    private String clientMode;

    /** 最后检查时间 */
    private OffsetDateTime lastCheckAt;

    /** 是否已验证 */
    private Boolean verified;

    /** 验证错误信息 */
    private String verifyError;

    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
