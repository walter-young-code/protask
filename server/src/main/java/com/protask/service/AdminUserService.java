package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.protask.common.BusinessException;
import com.protask.common.PasswordStrengthValidator;
import com.protask.common.ResultCode;
import com.protask.common.SqlUtil;
import com.protask.dto.AdminUserCreateRequest;
import com.protask.dto.AdminUserQuery;
import com.protask.dto.AdminUserUpdateRequest;
import com.protask.dto.AdminUserVO;
import com.protask.dto.PageResult;
import com.protask.dto.UserVO;
import com.protask.entity.Notification;
import com.protask.entity.Project;
import com.protask.entity.ProjectMember;
import com.protask.entity.Team;
import com.protask.entity.User;
import com.protask.mapper.NotificationMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectRole;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 管理后台用户服务：分页查询、创建、更新、重置密码、注销（仅系统管理员）。
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private static final Set<String> ALLOWED_ROLES = Set.of("admin", "user");
    private static final long MAX_PAGE_SIZE = 100;

    private final UserMapper userMapper;
    private final TeamMapper teamMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final NotificationMapper notificationMapper;

    /**
     * 分页查询用户（关键字模糊匹配用户名/昵称/邮箱，可按状态过滤）。
     */
    public PageResult<AdminUserVO> page(Long operatorId, AdminUserQuery query) {
        requireAdmin(operatorId);
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getKeyword())) {
            // 转义 LIKE 通配符，避免用户输入 %/_ 扩大匹配范围
            String kw = SqlUtil.escapeLike(query.getKeyword().trim());
            wrapper.and(w -> w.like(User::getUsername, kw)
                    .or().like(User::getNickname, kw)
                    .or().like(User::getFirstName, kw)
                    .or().like(User::getLastName, kw)
                    .or().like(User::getEmail, kw));
        }
        if (query.getStatus() != null) {
            wrapper.eq(User::getStatus, query.getStatus());
        }
        // 已注销用户不展示（注销不可逆，无需再次操作）
        wrapper.isNull(User::getDeletedAt);
        wrapper.orderByDesc(User::getCreatedAt).orderByAsc(User::getId);
        long size = query.getSize() == null || query.getSize() < 1 ? 10 : Math.min(query.getSize(), MAX_PAGE_SIZE);
        long page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        Page<User> result = userMapper.selectPage(new Page<>(page, size), wrapper);
        Map<Long, String> teamNames = teamNameMap();
        return PageResult.of(result, u -> toVO(u, teamNames));
    }

    /**
     * 创建用户：用户名唯一，角色/状态非法时拒绝。
     */
    public AdminUserVO create(Long operatorId, AdminUserCreateRequest request) {
        requireAdmin(operatorId);
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "auth.username.exists");
        }
        String role = StringUtils.hasText(request.getRole()) ? request.getRole() : "user";
        if (!ALLOWED_ROLES.contains(role)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "admin.roleInvalid");
        }
        Integer status = request.getStatus() != null ? request.getStatus() : 1;
        if (status != 0 && status != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "admin.statusInvalid");
        }
        PasswordStrengthValidator.validate(request.getPassword());
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : request.getUsername());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setRole(role);
        user.setStatus(status);
        user.setTeamId(resolveTeamId(request.getTeamId()));
        userMapper.insert(user);
        return toVO(user, teamNameMap());
    }

    /**
     * 更新用户（用户名不可修改）：不能修改自己的角色/状态；最后一名管理员不可降级或禁用。
     */
    public AdminUserVO update(Long operatorId, Long id, AdminUserUpdateRequest request) {
        requireAdmin(operatorId);
        User user = requireUser(id);
        boolean roleChanged = StringUtils.hasText(request.getRole()) && !request.getRole().equals(user.getRole());
        boolean statusChanged = request.getStatus() != null && !request.getStatus().equals(user.getStatus());
        // 防止管理员误操作自己导致失去管理权限或锁死账号
        if (id.equals(operatorId) && (roleChanged || statusChanged)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "admin.selfStatusLocked");
        }
        // 最后一名管理员保护：降级或禁用前必须存在其他管理员（已注销管理员不占名额）
        if ("admin".equals(user.getRole()) && (roleChanged || statusChanged)) {
            Long adminCount = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getRole, "admin")
                            .isNull(User::getDeletedAt));
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "admin.lastAdminLocked");
            }
        }
        if (roleChanged) {
            if (!ALLOWED_ROLES.contains(request.getRole())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "admin.roleInvalid");
            }
            user.setRole(request.getRole());
        }
        if (statusChanged) {
            if (request.getStatus() != 0 && request.getStatus() != 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "admin.statusInvalid");
            }
            user.setStatus(request.getStatus());
        }
        if (request.getNickname() != null) {
            user.setNickname(request.getNickname());
        }
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getTeamId() != null) {
            user.setTeamId(resolveTeamId(request.getTeamId()));
        }
        userMapper.updateById(user);
        return toVO(user, teamNameMap());
    }

    /**
     * 重置密码（不影响账号状态），重置后旧密码立即失效。
     */
    public void resetPassword(Long operatorId, Long id, String password) {
        requireAdmin(operatorId);
        PasswordStrengthValidator.validate(password);
        User user = requireUser(id);
        user.setPassword(passwordEncoder.encode(password));
        // 递增密码版本号：被重置用户的旧 token 立即失效
        user.setPasswordVersion(user.getPasswordVersion() != null
                ? user.getPasswordVersion() + 1 : 1);
        userMapper.updateById(user);
    }

    /**
     * 注销用户（不可逆）：项目负责人转移、移除成员关系、解除团队归属、清空通知库，
     * 任务/评论/附件等历史数据保留（用户记录软删除，创建人信息可继续展示），
     * 账号禁用且递增密码版本号，已签发 token 立即全部失效。
     * 项目接管人可手动指定（takeoverUserId），否则自动选择：项目内其他 admin 成员，
     * 无则转移给执行注销的管理员。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long operatorId, Long id, Long takeoverUserId) {
        requireAdmin(operatorId);
        // 防止管理员注销自己导致失去管理权限
        if (id.equals(operatorId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "admin.selfDeleteLocked");
        }
        User user = requireUser(id);
        // 最后一名管理员保护：注销管理员前必须存在其他管理员（已注销管理员不占名额）
        if ("admin".equals(user.getRole())) {
            Long adminCount = userMapper.selectCount(
                    new LambdaQueryWrapper<User>().eq(User::getRole, "admin")
                            .isNull(User::getDeletedAt));
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "admin.lastAdminLocked");
            }
        }
        // 手动指定接管人：必须为有效且未注销的用户，且不能是被注销用户本人
        if (takeoverUserId != null) {
            if (takeoverUserId.equals(id)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "admin.takeoverSelfLocked");
            }
            requireUser(takeoverUserId);
        }
        // 1. 项目负责人转移：优先指定接管人；否则项目内其他 admin 成员，无则转移给执行注销的管理员
        transferOwnedProjects(user, operatorId, takeoverUserId);
        // 2. 移除该用户的全部项目成员关系
        projectMemberMapper.delete(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getUserId, id));
        // 3. 清空该用户的通知库（站内消息随账号注销一并删除）
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, id));
        // 4. 解除团队归属
        user.setTeamId(null);
        // 5. 标记注销：禁用账号 + 置随机密码 + 递增密码版本号（所有旧 token 立即失效）
        user.setStatus(0);
        user.setDeletedAt(LocalDateTime.now());
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString().replace("-", "")));
        user.setPasswordVersion(passwordVersion(user) + 1);
        userMapper.updateById(user);
    }

    /** 将被注销用户负责的项目转移给接管人（指定接管人或自动选择，项目内容与成员不变） */
    private void transferOwnedProjects(User user, Long operatorId, Long takeoverUserId) {
        List<Project> owned = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getOwnerId, user.getId())
                .isNull(Project::getDeleteAt));
        for (Project project : owned) {
            Long newOwnerId;
            if (takeoverUserId != null) {
                newOwnerId = takeoverUserId;
            } else {
                newOwnerId = findAdminMember(project.getId(), user.getId());
                if (newOwnerId == null) {
                    newOwnerId = operatorId;
                }
            }
            project.setOwnerId(newOwnerId);
            project.setModifiedBy(operatorId);
            projectMapper.updateById(project);
        }
    }

    /** 查询项目内最早加入的 admin 成员（排除指定用户），无则返回 null */
    private Long findAdminMember(Long projectId, Long excludeUserId) {
        List<ProjectMember> admins = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .eq(ProjectMember::getRole, ProjectRole.ADMIN.getValue())
                        .ne(ProjectMember::getUserId, excludeUserId)
                        .orderByAsc(ProjectMember::getJoinedAt));
        return admins.isEmpty() ? null : admins.get(0).getUserId();
    }

    /** 密码版本号（历史数据为 null 时按 0 处理） */
    private static int passwordVersion(User user) {
        return user.getPasswordVersion() != null ? user.getPasswordVersion() : 0;
    }

    /** 校验操作者是否为系统管理员 */
    private void requireAdmin(Long operatorId) {
        if (!permissionService.isSystemAdmin(operatorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin.permissionDenied");
        }
    }

    private User requireUser(Long id) {
        User user = userMapper.selectById(id);
        // 已注销用户不可再操作（列表不展示，接口层面防御）
        if (user == null || user.getDeletedAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "user.notFound");
        }
        return user;
    }

    /** 解析团队ID：null 原样；0 表示清除团队（返回 null）；>0 校验存在后返回；<0 拒绝 */
    private Long resolveTeamId(Long teamId) {
        if (teamId == null || teamId == 0) {
            return null;
        }
        if (teamId < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "team.invalidId");
        }
        Team team = teamMapper.selectById(teamId);
        if (team == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "team.notFound");
        }
        return teamId;
    }

    /** 团队 ID -> 名称映射（一次查询，避免 N+1） */
    private Map<Long, String> teamNameMap() {
        return teamMapper.selectList(null).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
    }

    private static AdminUserVO toVO(User user, Map<Long, String> teamNames) {
        AdminUserVO vo = new AdminUserVO();
        BeanUtils.copyProperties(user, vo);
        vo.setTeamName(user.getTeamId() == null ? null : teamNames.get(user.getTeamId()));
        vo.setFullName(UserVO.fullNameOf(user.getFirstName(), user.getLastName()));
        return vo;
    }
}
