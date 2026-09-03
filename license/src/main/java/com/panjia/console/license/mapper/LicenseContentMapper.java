package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.license.domain.LicenseContent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * License 载荷 Mapper
 */
@Mapper
public interface LicenseContentMapper extends BaseMapper<LicenseContent> {

    /**
     * 查询指定授权码的当前版本（is_current=TRUE）
     *
     * @param authCodeId 授权码 ID
     * @return License 内容实体
     */
    @Select("SELECT * FROM auth.t_license_content " +
            "WHERE auth_code_id = #{authCodeId} AND is_current = TRUE")
    LicenseContent selectCurrent(@Param("authCodeId") Long authCodeId);

    /**
     * 查询当前最大 license_version
     *
     * @param authCodeId 授权码 ID
     * @return 最大版本号
     */
    @Select("SELECT COALESCE(MAX(license_version), 0) FROM auth.t_license_content WHERE auth_code_id = #{authCodeId}")
    Integer selectMaxVersion(@Param("authCodeId") Long authCodeId);
}
