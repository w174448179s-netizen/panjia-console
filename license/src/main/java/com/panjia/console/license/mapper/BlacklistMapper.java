package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.license.domain.Blacklist;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 黑名单 Mapper
 */
@Mapper
public interface BlacklistMapper extends BaseMapper<Blacklist> {

    /**
     * 根据授权码 ID 查询黑名单记录
     *
     * @param authCodeId 授权码 ID
     * @return 黑名单实体（不存在则返回 null）
     */
    @Select("SELECT * FROM auth.t_blacklist WHERE auth_code_id = #{authCodeId}")
    Blacklist selectByAuthCodeId(@Param("authCodeId") Long authCodeId);
}
