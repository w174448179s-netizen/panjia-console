package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.license.domain.MultiInstancePending;
import org.apache.ibatis.annotations.Mapper;

/**
 * 多实例两阶段确认 Mapper
 */
@Mapper
public interface MultiInstancePendingMapper extends BaseMapper<MultiInstancePending> {
}
