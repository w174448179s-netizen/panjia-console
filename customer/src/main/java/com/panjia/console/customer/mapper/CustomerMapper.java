package com.panjia.console.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.customer.domain.Customer;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户档案 Mapper
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
