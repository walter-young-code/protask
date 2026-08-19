package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目分类实体（全局共享，名称唯一）。
 */
@Data
@TableName("project_category")
public class ProjectCategory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分类名称 */
    private String name;

    /** 创建人(用户ID) */
    private Long createdBy;

    private LocalDateTime createdAt;
}
