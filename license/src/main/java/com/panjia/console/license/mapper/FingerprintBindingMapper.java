package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
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
}
