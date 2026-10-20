package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.dto.UserVO;
import com.protask.entity.Team;
import com.protask.entity.User;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户服务。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    private final TeamMapper teamMapper;

    private final PermissionService permissionService;

    /**
     * 查询所有启用状态的用户（用于任务分配、添加成员等下拉选择，排除已注销），并附带所属团队名称。
     * 非系统管理员仅返回选人所需的公开字段（username/nickname/姓名/头像/团队），
     * 不返回 email 与系统角色，避免任意登录用户（含分享访客）拉取完整用户目录。
     */
    public List<UserVO> listActiveUsers(Long currentUserId) {
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, 1)
                        .isNull(User::getDeletedAt)
                        .orderByAsc(User::getUsername));
        Map<Long, String> teamNames = teamMapper.selectList(null).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
        boolean systemAdmin = permissionService.isSystemAdmin(currentUserId);
        return users.stream().map(u -> {
            UserVO vo = AuthService.toVO(u);
            vo.setTeamName(u.getTeamId() == null ? null : teamNames.get(u.getTeamId()));
            if (!systemAdmin) {
                vo.setEmail(null);
                vo.setRole(null);
            }
            return vo;
        }).toList();
    }
}
