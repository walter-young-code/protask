package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.protask.entity.TaskDependency;
import org.apache.ibatis.annotations.Mapper;

/**
 * 任务依赖关系 Mapper。
 */
@Mapper
public interface TaskDependencyMapper extends BaseMapper<TaskDependency> {
}