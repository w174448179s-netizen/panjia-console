package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.console.license.api.dto.AppClientView;
import com.panjia.console.license.domain.FingerprintBinding;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 指纹绑定 Mapper
 */
@Mapper
public interface FingerprintBindingMapper extends BaseMapper<FingerprintBinding> {

    /**
     * 查询指定授权码的 ACTIVE 绑定（加行锁）
     *
     * @param authCodeId 授权码 ID
     * @return 指纹绑定实体
     */
    @Select("SELECT * FROM auth.t_fingerprint_binding " +
            "WHERE auth_code_id = #{authCodeId} AND status = 'ACTIVE' FOR UPDATE")
    FingerprintBinding selectActiveForUpdate(@Param("authCodeId") Long authCodeId);

    /**
     * 查询最近一条 INVALIDATED 绑定（用于取消换机时恢复）
     *
     * @param authCodeId 授权码 ID
     * @return 指纹绑定实体
     */
    @Select("SELECT * FROM auth.t_fingerprint_binding " +
            "WHERE auth_code_id = #{authCodeId} AND status = 'INVALIDATED' " +
            "ORDER BY invalidated_at DESC LIMIT 1")
    FingerprintBinding selectLatestInvalidated(@Param("authCodeId") Long authCodeId);

    /**
     * 分页查询应用端视图（联表 fingerprint_binding + auth_code + 最新心跳）
     * <p>
     * 每个指纹绑定关联其所属授权码，并取该授权码最新一条心跳（LATERAL）。
     *
     * @param page 分页参数
     * @return 应用端视图分页
     */
    @Select("SELECT ac.auth_code AS authCode, ac.customer_no AS customerNo, " +
            "fb.fp_hash AS fpHash, fb.status AS status, fb.bound_at AS boundAt, " +
            "fb.invalidated_at AS invalidatedAt, fb.invalidate_reason AS invalidateReason, " +
            "hb.instance_id AS instanceId, hb.received_at AS lastHeartbeatAt, hb.client_mode AS clientMode " +
            "FROM auth.t_fingerprint_binding fb " +
            "JOIN auth.t_auth_code ac ON fb.auth_code_id = ac.id " +
            "LEFT JOIN LATERAL (" +
            "  SELECT instance_id, received_at, client_mode " +
            "  FROM auth.t_heartbeat_record " +
            "  WHERE auth_code_id = fb.auth_code_id " +
            "  ORDER BY received_at DESC LIMIT 1" +
            ") hb ON TRUE " +
            "ORDER BY fb.bound_at DESC")
    IPage<AppClientView> pageAppClients(Page<AppClientView> page);
}
