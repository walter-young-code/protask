package com.protask.dto;

import lombok.Data;

/**
 * 任务依赖视图对象。
 */
@Data
public class DependencyVO {

    private Long id;

    private Long projectId;

    private Long predecessorId;

    private Long successorId;

    /** 依赖类型: FS/SS/FF/SF */
    private String type;

    /** 延迟天数 */
    private Integer lag;
}