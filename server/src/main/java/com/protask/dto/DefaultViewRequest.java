package com.protask.dto;

import lombok.Data;

/**
 * 设置项目默认视图请求：viewId 为 null 时清除默认视图。
 */
@Data
public class DefaultViewRequest {

    /** 视图块 ID（null=清除默认视图） */
    private Long viewId;
}
