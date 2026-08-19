package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.dto.UserVO;
import com.protask.entity.Team;
import com.protask.entity.User;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
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

    /**
     * 查询所有启用状态的用户（用于任务分配等下拉选择，排除已注销），并附带所属团队名称。
     */
    public List<UserVO> listActiveUsers() {
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .eq(User::getStatus, 1)
                        .isNull(User::getDeletedAt)
                        .orderByAsc(User::getUsername));
        Map<Long, String> teamNames = teamMapper.selectList(null).stream()
                .collect(Collectors.toMap(Team::getId, Team::getName, (a, b) -> a));
        return users.stream().map(u -> {
            UserVO vo = AuthService.toVO(u);
            vo.setTeamName(u.getTeamId() == null ? null : teamNames.get(u.getTeamId()));
            return vo;
        }).toList();
    }
}
