package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.license.domain.AuthCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 授权码 Mapper
 */
@Mapper
public interface AuthCodeMapper extends BaseMapper<AuthCode> {

    /**
     * 根据授权码查询（加行锁，用于 activate 并发控制）
     *
     * @param authCode 授权码
     * @return 授权码实体
     */
    @Select("SELECT * FROM auth.t_auth_code WHERE auth_code = #{authCode} FOR UPDATE")
    AuthCode selectByAuthCodeForUpdate(@Param("authCode") String authCode);

    /**
     * 根据 requestId 查询（加行锁，用于签发幂等）
     *
     * @param requestId 幂等键
     * @return 授权码实体
     */
    @Select("SELECT * FROM auth.t_auth_code WHERE request_id = #{requestId} FOR UPDATE")
    AuthCode selectByRequestIdForUpdate(@Param("requestId") String requestId);
}
