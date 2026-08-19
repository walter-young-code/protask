package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目视图对象（含负责人昵称与当前用户在项目中的角色）。
 */
@Data
public class ProjectVO {

    private Long id;

    private String name;

    private String description;

    /** 项目类型: O 公开, P 私有 */
    private String type;

    /** 项目图标 */
    private String icon;

    /** 项目分类ID（NULL=未分类） */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 卡片动态属性定义模板(JSON 数组字符串) */
    private String cardProperties;

    /** 项目扩展属性(JSON 对象字符串，如默认视图配置 defaultViewId) */
    private String properties;

    /** 是否项目模板 */
    private Boolean isTemplate;

    private Long ownerId;

    /** 负责人昵称 */
    private String ownerName;

    private String status;

    /** 当前登录用户在项目中的生效角色: viewer/commenter/editor/admin */
    private String myRole;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
