package com.panjia.console.customer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;

/**
 * 客户档案实体
 * <p>
 * 对应表：customer.pj_customer
 */
@Data
@TableName(value = "customer.pj_customer", autoResultMap = true)
public class Customer implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户编号 */
    private String customerNo;

    /** 客户名称 */
    private String customerName;

    /** 联系人 */
    private String contactPerson;

    /** 联系电话 */
    private String contactPhone;

    /** 实例 ID */
    private String instanceId;

    /** 当前版本 */
    private String currentVersion;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
