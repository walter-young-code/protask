package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员视图对象（含用户基本信息）。
 */
@Data
public class ProjectMemberVO {

    private Long userId;

    private String username;

    private String nickname;

    /** 姓名：First + Last 拼接，未填写时为空 */
    private String fullName;

    private String avatar;

    /** 成员角色: viewer/commenter/editor/admin */
    private String role;

    private LocalDateTime joinedAt;
}
