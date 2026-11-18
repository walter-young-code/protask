package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务依赖关系实体（仅添加 WBS 编码属性的项目启用）。
 */
@Data
@TableName("task_dependency")
public class TaskDependency {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目ID */
    private Long projectId;

    /** 前置任务ID */
    private Long predecessorId;

    /** 后置任务ID */
    private Long successorId;

    /** 依赖类型: FS/SS/FF/SF（当前仅支持 FS） */
    private String type;

    /** 延迟天数（lag 为 MySQL 8.0 保留字，列名需反引号包裹） */
    @TableField("`lag`")
    private Integer lag;

    private LocalDateTime createAt;
}