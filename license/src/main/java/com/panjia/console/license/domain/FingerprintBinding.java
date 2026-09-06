package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 指纹绑定实体（唯一指纹权威）
 * <p>
 * 对应表：auth.t_fingerprint_binding
 * <p>
 * 每授权码至多 1 条 ACTIVE 状态的绑定（partial unique index 保证）。
 */
@Data
@TableName(value = "auth.t_fingerprint_binding", autoResultMap = true)
public class FingerprintBinding implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 授权码 ID */
    private Long authCodeId;

    /** 指纹 SHA-256 哈希 */
    private String fpHash;

    /** 原始设备指纹（激活时上报，用于展示） */
    private String fingerprint;

    /** 客户端产品版本（激活时上报） */
    private String productVersion;

    /** 状态：ACTIVE/INVALIDATED */
    private String status;

    /** 绑定时间 */
    private OffsetDateTime boundAt;

    /** 失效时间 */
    private OffsetDateTime invalidatedAt;

    /** 失效原因 */
    private String invalidateReason;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
