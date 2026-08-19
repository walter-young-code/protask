package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目分类视图对象。
 */
@Data
public class CategoryVO {

    private Long id;

    private String name;

    /** 创建人(用户ID) */
    private Long createdBy;

    private LocalDateTime createdAt;
}
