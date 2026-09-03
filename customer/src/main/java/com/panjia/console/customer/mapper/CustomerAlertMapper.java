package com.panjia.console.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.customer.domain.CustomerAlert;
import org.apache.ibatis.annotations.Mapper;

/**
 * 客户告警 Mapper
 */
@Mapper
public interface CustomerAlertMapper extends BaseMapper<CustomerAlert> {
}
