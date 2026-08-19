package com.protask.permission;

/**
 * 项目级细粒度权限点（对齐 Focalboard model/permission.go 的 Board 权限定义）。
 */
public enum ProjectPermission {

    /** 查看项目及其任务 */
    VIEW_PROJECT("view_project"),

    /** 对任务卡片发表评论 */
    COMMENT_PROJECT_CARDS("comment_project_cards"),

    /** 增删改任务卡片/视图等内容块 */
    MANAGE_PROJECT_CARDS("manage_project_cards"),

    /** 管理项目的动态属性定义 */
    MANAGE_PROJECT_PROPERTIES("manage_project_properties"),

    /** 管理项目成员与角色 */
    MANAGE_PROJECT_ROLES("manage_project_roles"),

    /** 分享项目（对外可见性控制） */
    SHARE_PROJECT("share_project"),

    /** 删除项目 */
    DELETE_PROJECT("delete_project"),

    /** 修改项目类型/最低角色 */
    MANAGE_PROJECT_TYPE("manage_project_type"),

    /** 删除他人评论 */
    DELETE_OTHERS_COMMENTS("delete_others_comments");

    private final String id;

    ProjectPermission(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
