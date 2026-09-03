package com.panjia.console.license.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 测试码实体
 * <p>
 * 对应表：auth.t_test_code
 */
@Data
@TableName(value = "auth.t_test_code", autoResultMap = true)
public class TestCode implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 测试码 */
    private String testCode;

    /** 关联授权码 ID */
    private Long authCodeId;

    /** 过期时间 */
    private OffsetDateTime expireAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;
}
