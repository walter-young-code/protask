package com.protask.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建任务依赖请求（支持 FS/SS/FF/SF 四种类型）。
 */
@Data
public class DependencyRequest {

    @NotNull(message = "{validation.dependencyPredecessor.notNull}")
    private Long predecessorId;

    @NotNull(message = "{validation.dependencySuccessor.notNull}")
    private Long successorId;

    /** 依赖类型 FS/SS/FF/SF，缺省 FS */
    private String type;

    /** 延迟天数 [-365, 365]，缺省 0，负数=提前量 */
    private Integer lag;
}