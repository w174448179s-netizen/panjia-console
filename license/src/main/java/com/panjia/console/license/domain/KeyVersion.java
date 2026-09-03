package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 密钥版本实体
 * <p>
 * 对应表：auth.t_key_version
 * <p>
 * V1 仅一条当前记录（keyVersion=1），不做在线轮换。
 * 密钥更换需发新版客户端 + 统一切换签发密钥。
 */
@Data
@TableName(value = "auth.t_key_version", autoResultMap = true)
public class KeyVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 密钥版本号 */
    private Integer keyVersion;

    /** 公钥（PEM 格式） */
    private String publicKey;

    /** 是否当前版本 */
    private Boolean isCurrent;

    /** 激活时间 */
    private OffsetDateTime activatedAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
