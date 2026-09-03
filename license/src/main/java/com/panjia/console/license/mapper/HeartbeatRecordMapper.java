package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.license.domain.HeartbeatRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 心跳记录 Mapper
 */
@Mapper
public interface HeartbeatRecordMapper extends BaseMapper<HeartbeatRecord> {

    /**
     * 将一条心跳记录插入归档表
     *
     * @param record 心跳记录
     * @return 插入行数
     */
    @Insert("INSERT INTO auth.t_heartbeat_archive " +
            "(id, auth_code_id, customer_no, fp_hash, instance_id, " +
            " reported_at, received_at, current_stores, current_users, client_mode, raw) " +
            "VALUES (#{id}, #{authCodeId}, #{customerNo}, #{fpHash}, #{instanceId}, " +
            " #{reportedAt}, #{receivedAt}, #{currentStores}, #{currentUsers}, #{clientMode}, #{raw})")
    int insertArchive(@Param("record") HeartbeatRecord record);
}
