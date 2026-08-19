package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.protask.entity.SiteSetting;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站点设置 Mapper。
 */
@Mapper
public interface SiteSettingMapper extends BaseMapper<SiteSetting> {
}
