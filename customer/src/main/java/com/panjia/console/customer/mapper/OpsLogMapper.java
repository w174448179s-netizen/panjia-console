package com.panjia.console.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.customer.domain.OpsLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作审计日志 Mapper
 */
@Mapper
public interface OpsLogMapper extends BaseMapper<OpsLog> {
}
