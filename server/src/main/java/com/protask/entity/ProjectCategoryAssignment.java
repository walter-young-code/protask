package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目分类归属实体（按用户独立：同一项目在不同用户的侧边栏可有不同归类）。
 */
@Data
@TableName("project_category_assignment")
public class ProjectCategoryAssignment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归类所属用户 */
    private Long userId;

    /** 项目ID */
    private Long projectId;

    /** 分类ID（必须属于 userId） */
    private Long categoryId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
