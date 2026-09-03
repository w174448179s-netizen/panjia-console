package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 多实例两阶段确认实体
 * <p>
 * 对应表：auth.t_multi_instance_pending
 * <p>
 * 同一 authCode 在 24h 窗口内出现不同 fingerprint 时，
 * 首次 → 插入 pending + confirm_count=1；
 * 累计达到阈值 → 确认拉黑。
 * REBINDING 状态整体跳过此检测。
 */
@Data
@TableName(value = "auth.t_multi_instance_pending", autoResultMap = true)
public class MultiInstancePending implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码 ID */
    private Long authCodeId;

    /** 指纹哈希 */
    private String fpHash;

    /** 确认次数 */
    private Integer confirmCount;

    /** 首次出现时间 */
    private OffsetDateTime firstSeenAt;

    /** 最近出现时间 */
    private OffsetDateTime lastSeenAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
