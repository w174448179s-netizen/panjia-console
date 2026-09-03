package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.license.domain.HeartbeatRecord;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 心跳记录 Mapper
 */
@Mapper
public interface HeartbeatRecordMapper extends BaseMapper<HeartbeatRecord> {

    /**
     * 将一条心跳记录插入归档表
     */
    @Insert("INSERT INTO auth.t_heartbeat_archive " +
            "(id, auth_code_id, customer_no, fp_hash, instance_id, " +
            " reported_at, received_at, current_stores, current_users, client_mode, raw) " +
            "VALUES (#{id}, #{authCodeId}, #{customerNo}, #{fpHash}, #{instanceId}, " +
            " #{reportedAt}, #{receivedAt}, #{currentStores}, #{currentUsers}, #{clientMode}, #{raw})")
    int insertArchive(@Param("record") HeartbeatRecord record);

    /**
     * 分页查询每个客户的最新心跳记录（DISTINCT ON）
     */
    @Select("SELECT DISTINCT ON (customer_no) * FROM auth.t_heartbeat_record " +
            "WHERE customer_no IS NOT NULL " +
            "ORDER BY customer_no, received_at DESC")
    IPage<HeartbeatRecord> selectLatestPerCustomer(Page<HeartbeatRecord> page);
}
