package com.panjia.console.customer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.customer.domain.BackupRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 备份记录 Mapper
 */
@Mapper
public interface BackupRecordMapper extends BaseMapper<BackupRecord> {
}
