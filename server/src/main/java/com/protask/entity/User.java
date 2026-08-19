package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体。
 */
@Data
@TableName("sys_user")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** BCrypt 加密密码 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 名（First Name） */
    private String firstName;

    /** 姓（Last Name） */
    private String lastName;

    /** 邮箱 */
    private String email;

    /** 头像 URL */
    private String avatar;

    /** 所属团队 ID（可空） */
    private Long teamId;

    /** 系统角色: admin/user */
    private String role;

    /** 状态: 1 启用, 0 禁用 */
    private Integer status;

    /** 密码版本号: 修改/重置密码时 +1，用于使已签发的旧 token 失效 */
    private Integer passwordVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 注销时间(软删除标记)，为 null 表示未注销 */
    private LocalDateTime deletedAt;
}
