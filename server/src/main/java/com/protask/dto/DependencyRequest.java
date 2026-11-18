package com.protask.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建任务依赖请求（当前仅支持 FS 完成-开始）。
 */
@Data
public class DependencyRequest {

    @NotNull(message = "{validation.dependencyPredecessor.notNull}")
    private Long predecessorId;

    @NotNull(message = "{validation.dependencySuccessor.notNull}")
    private Long successorId;

    /** 依赖类型，缺省 FS */
    private String type;

    /** 延迟天数，缺省 0 */
    private Integer lag;
}