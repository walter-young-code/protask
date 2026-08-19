package com.protask.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 添加/变更项目成员请求。
 */
@Data
public class ProjectMemberRequest {

    /** 目标用户ID */
    @NotNull(message = "{validation.memberUserId.notNull}")
    private Long userId;

    /** 成员角色: viewer/commenter/editor/admin */
    @NotNull(message = "{validation.memberRole.notNull}")
    @Pattern(regexp = "^(viewer|commenter|editor|admin)$", message = "{validation.memberRole.pattern}")
    private String role;
}
