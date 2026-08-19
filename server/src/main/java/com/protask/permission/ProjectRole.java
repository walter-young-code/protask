package com.protask.permission;

/**
 * 项目内成员角色（对齐 Focalboard BoardRole：viewer/commenter/editor/admin）。
 * 按枚举顺序定义权限等级，NONE 表示无任何角色。
 */
public enum ProjectRole {

    NONE(""),
    VIEWER("viewer"),
    COMMENTER("commenter"),
    EDITOR("editor"),
    ADMIN("admin");

    private final String value;

    ProjectRole(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 从数据库存储值解析角色，未知或空值返回 NONE。
     */
    public static ProjectRole fromValue(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        for (ProjectRole role : values()) {
            if (role.value.equals(value)) {
                return role;
            }
        }
        return NONE;
    }

    /**
     * 角色等级不低于 other。
     */
    public boolean isAtLeast(ProjectRole other) {
        return this.ordinal() >= other.ordinal();
    }
}
