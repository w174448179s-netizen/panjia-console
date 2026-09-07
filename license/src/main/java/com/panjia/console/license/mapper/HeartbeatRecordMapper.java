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
            " reported_at, received_at, current_stores, current_users, client_mode, client_ip, raw) " +
            "VALUES (#{id}, #{authCodeId}, #{customerNo}, #{fpHash}, #{instanceId}, " +
            " #{reportedAt}, #{receivedAt}, #{currentStores}, #{currentUsers}, #{clientMode}, #{clientIp}, #{raw})")
    int insertArchive(@Param("record") HeartbeatRecord record);

    /**
     * 分页查询每个客户的最新心跳记录（DISTINCT ON）
     */
    @Select("SELECT DISTINCT ON (customer_no) * FROM auth.t_heartbeat_record " +
            "WHERE customer_no IS NOT NULL " +
            "ORDER BY customer_no, received_at DESC")
    IPage<HeartbeatRecord> selectLatestPerCustomer(Page<HeartbeatRecord> page);

    /**
     * 查询同一授权码在指定时间窗口内来自不同 IP 的最近心跳
     * <p>
     * 用于 IP 多实例检测：如果同一 authCode 在短时间内从不同 IP 心跳，
     * 说明授权码被同时使用在多台机器上。
     *
     * @param authCodeId  授权码 ID
     * @param currentIp   当前心跳的客户端 IP（排除自身）
     * @param since       时间窗口起点
     * @return 不同 IP 的心跳记录列表（有记录说明检测到多实例）
     */
    @Select("SELECT * FROM auth.t_heartbeat_record " +
            "WHERE auth_code_id = #{authCodeId} " +
            "AND client_ip IS NOT NULL " +
            "AND client_ip != #{currentIp} " +
            "AND received_at >= #{since} " +
            "ORDER BY received_at DESC " +
            "LIMIT 5")
    java.util.List<HeartbeatRecord> selectRecentByDifferentIp(
            @Param("authCodeId") Long authCodeId,
            @Param("currentIp") String currentIp,
            @Param("since") java.time.OffsetDateTime since);
}
