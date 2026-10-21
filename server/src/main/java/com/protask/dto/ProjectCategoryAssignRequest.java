package com.protask.dto;

import lombok.Data;

/**
 * 设置当前用户项目归类请求。
 */
@Data
public class ProjectCategoryAssignRequest {

    /** 分类ID：null 或 0 表示未分类（清除归类） */
    private Long categoryId;
}
