package com.panjia.console.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 产品版本实体
 * <p>
 * 对应表：customer.pj_product_version
 */
@Data
@TableName(value = "customer.pj_product_version", autoResultMap = true)
public class ProductVersion implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 版本号 */
    private String version;

    /** 文件路径 */
    private String filePath;

    /** 文件大小（字节） */
    private Long fileSize;

    /** SHA256 校验值 */
    private String sha256;

    /** 发布说明 */
    private String releaseNote;

    /** 发布时间 */
    private OffsetDateTime releasedAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
