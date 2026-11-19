package com.protask.dto;

import lombok.Data;

/**
 * 修改任务依赖请求（仅允许改类型/lag，字段缺省表示不修改）。
 */
@Data
public class DependencyPatchRequest {

    /** 依赖类型 FS/SS/FF/SF */
    private String type;

    /** 延迟天数 [-365, 365]，负数=提前量 */
    private Integer lag;
}