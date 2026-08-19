package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目成员实体（项目与用户的多对多关系）。
 */
@Data
@TableName("project_member")
public class ProjectMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目ID */
    private Long projectId;

    /** 用户ID */
    private Long userId;

    /** 项目角色: owner/admin/member */
    private String role;

    /** 加入时间 */
    private LocalDateTime joinedAt;
}
