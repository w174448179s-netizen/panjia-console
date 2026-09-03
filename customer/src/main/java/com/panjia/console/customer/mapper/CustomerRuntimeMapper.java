package com.panjia.console.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.customer.domain.CustomerRuntime;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户运行时快照 Mapper
 */
@Mapper
public interface CustomerRuntimeMapper extends BaseMapper<CustomerRuntime> {
}
