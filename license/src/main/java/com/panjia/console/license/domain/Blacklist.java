package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 黑名单实体
 * <p>
 * 对应表：auth.t_blacklist
 * <p>
 * REVOKE 原因的黑名单不可通过 removeFromBlacklist 移除，必须 restore 恢复授权。
 */
@Data
@TableName(value = "auth.t_blacklist", autoResultMap = true)
public class Blacklist implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码 ID */
    private Long authCodeId;

    /** 原因：REVOKE/MANUAL/MULTI_INSTANCE */
    private String reason;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 创建人 */
    private String createdBy;
}
