package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.protask.entity.ProjectMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 项目成员 Mapper。
 */
@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMember> {
}
