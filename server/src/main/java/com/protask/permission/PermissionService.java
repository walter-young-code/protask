package com.protask.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.entity.Project;
import com.protask.entity.ProjectMember;
import com.protask.entity.User;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 项目权限服务（对齐 Focalboard services/permissions 的角色-权限矩阵设计）。
 * <p>
 * 角色-权限矩阵：
 * <ul>
 *   <li>admin: 全部权限</li>
 *   <li>editor: 查看 + 管理卡片 + 管理属性</li>
 *   <li>commenter: 查看 + 评论卡片</li>
 *   <li>viewer: 仅查看</li>
 * </ul>
 * 系统管理员（sys_user.role=admin）一律放行；
 * 公开项目（type=O）的非成员仅授予查看权限；
 * 生效角色 = 成员角色（非成员为 NONE），项目负责人等同 admin。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private static final Map<ProjectRole, Set<ProjectPermission>> ROLE_MATRIX;

    static {
        ROLE_MATRIX = new EnumMap<>(ProjectRole.class);
        ROLE_MATRIX.put(ProjectRole.ADMIN, EnumSet.allOf(ProjectPermission.class));
        ROLE_MATRIX.put(ProjectRole.EDITOR, EnumSet.of(
                ProjectPermission.VIEW_PROJECT,
                ProjectPermission.COMMENT_PROJECT_CARDS,
                ProjectPermission.MANAGE_PROJECT_CARDS,
                ProjectPermission.MANAGE_PROJECT_PROPERTIES));
        ROLE_MATRIX.put(ProjectRole.COMMENTER, EnumSet.of(
                ProjectPermission.VIEW_PROJECT,
                ProjectPermission.COMMENT_PROJECT_CARDS));
        ROLE_MATRIX.put(ProjectRole.VIEWER, EnumSet.of(
                ProjectPermission.VIEW_PROJECT));
        ROLE_MATRIX.put(ProjectRole.NONE, EnumSet.noneOf(ProjectPermission.class));
    }

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;

    /** 分享凭证请求头：公开项目非成员凭此只读访问（值 = 项目 shareId） */
    private static final String SHARE_TOKEN_HEADER = "X-Share-Token";

    /**
     * 判断用户是否拥有指定项目的某项权限。
     */
    public boolean hasPermissionToProject(Long userId, Long projectId, ProjectPermission permission) {
        if (userId == null || projectId == null || permission == null) {
            return false;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeleteAt() != null) {
            return false;
        }
        // 模板项目不参与常规项目权限（仅通过模板库接口只读访问），对所有人（含系统管理员）生效
        if (Boolean.TRUE.equals(project.getIsTemplate())) {
            return false;
        }
        if (isSystemAdmin(userId)) {
            return true;
        }
        // 归档项目仅系统管理员可访问（管理后台恢复/删除），成员视为无权限
        if ("archived".equals(project.getStatus())) {
            return false;
        }
        ProjectRole role = effectiveRole(userId, project);
        if (role == ProjectRole.NONE && "O".equals(project.getType())) {
            // 公开项目非成员：仅持有效分享链接的用户可查看（不再对全员可见）
            return permission == ProjectPermission.VIEW_PROJECT && shareTokenMatches(project);
        }
        return ROLE_MATRIX.get(role).contains(permission);
    }

    /**
     * 判断当前请求是否为该项目的分享访客（请求携带与项目 shareId 匹配的分享凭证）。
     * 分享访客仅获 VIEW_PROJECT 只读权限，供项目详情设置展示角色用。
     */
    public boolean isShareVisitor(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        return project != null && project.getDeleteAt() == null
                && !Boolean.TRUE.equals(project.getIsTemplate())
                && shareTokenMatches(project);
    }

    /** 当前请求携带的分享凭证是否与项目 shareId 匹配（常量时间比较） */
    private boolean shareTokenMatches(Project project) {
        if (project == null || project.getShareId() == null) {
            return false;
        }
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return false;
        }
        String token = attrs.getRequest().getHeader(SHARE_TOKEN_HEADER);
        if (token == null || token.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                project.getShareId().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 权限校验，不满足时抛出 403 业务异常。
     */
    public void checkPermission(Long userId, Long projectId, ProjectPermission permission) {
        if (!hasPermissionToProject(userId, projectId, permission)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "project.permissionDenied");
        }
    }

    /**
     * 计算用户在项目中的生效角色：负责人等同 admin，其余取成员角色，非成员为 NONE。
     */
    public ProjectRole getEffectiveRole(Long userId, Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            return ProjectRole.NONE;
        }
        return effectiveRole(userId, project);
    }

    private ProjectRole effectiveRole(Long userId, Project project) {
        if (userId.equals(project.getOwnerId())) {
            return ProjectRole.ADMIN;
        }
        ProjectMember member = projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, project.getId())
                .eq(ProjectMember::getUserId, userId));
        return member != null
                ? ProjectRole.fromValue(member.getRole())
                : ProjectRole.NONE;
    }

    /**
     * 判断用户是否为系统管理员（sys_user.role=admin），供管理后台等全局管理能力复用。
     */
    public boolean isSystemAdmin(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && "admin".equals(user.getRole());
    }
}
