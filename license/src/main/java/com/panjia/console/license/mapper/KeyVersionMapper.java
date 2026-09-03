package com.panjia.console.license.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.panjia.console.license.domain.KeyVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 密钥版本 Mapper
 */
@Mapper
public interface KeyVersionMapper extends BaseMapper<KeyVersion> {

    /**
     * 查询当前使用的密钥版本
     *
     * @return 当前密钥版本
     */
    @Select("SELECT * FROM auth.t_key_version WHERE is_current = TRUE ORDER BY key_version DESC LIMIT 1")
    KeyVersion selectCurrent();
}
