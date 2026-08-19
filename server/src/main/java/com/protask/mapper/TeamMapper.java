package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.protask.entity.Team;
import org.apache.ibatis.annotations.Mapper;

/**
 * 团队 Mapper。
 */
@Mapper
public interface TeamMapper extends BaseMapper<Team> {
}
