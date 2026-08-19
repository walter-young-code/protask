package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.common.SqlUtil;
import com.protask.dto.PageResult;
import com.protask.dto.TeamRequest;
import com.protask.dto.TeamVO;
import com.protask.entity.Team;
import com.protask.entity.User;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 管理后台团队服务：分页查询、全量列表、创建、重命名、删除（仅系统管理员）。
 */
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final long MAX_PAGE_SIZE = 100;

    private final TeamMapper teamMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;

    /**
     * 分页查询团队（名称模糊匹配，含成员数）。
     */
    public PageResult<TeamVO> page(Long operatorId, String keyword, Long page, Long size) {
        requireAdmin(operatorId);
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) {
            // 转义 LIKE 通配符，避免用户输入 %/_ 扩大匹配范围
            wrapper.like(Team::getName, SqlUtil.escapeLike(keyword.trim()));
        }
        wrapper.orderByAsc(Team::getId);
        long pageNo = page == null || page < 1 ? 1 : page;
        long pageSize = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Page<Team> result = teamMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        Map<Long, Long> countMap = memberCountMap();
        return PageResult.of(result, t -> toVO(t, countMap));
    }

    /**
     * 全量团队列表（含成员数），供用户管理下拉选择。
     */
    public List<TeamVO> listAll(Long operatorId) {
        requireAdmin(operatorId);
        Map<Long, Long> countMap = memberCountMap();
        return teamMapper.selectList(new LambdaQueryWrapper<Team>().orderByAsc(Team::getId))
                .stream()
                .map(t -> toVO(t, countMap))
                .toList();
    }

    /**
     * 创建团队：名称必填且唯一。
     */
    public TeamVO create(Long operatorId, TeamRequest request) {
        requireAdmin(operatorId);
        String name = request.getName().trim();
        requireUniqueName(name, null);
        Team team = new Team();
        team.setName(name);
        teamMapper.insert(team);
        return toVO(team, Map.of());
    }

    /**
     * 重命名团队：名称唯一（排除自身）。
     */
    public TeamVO update(Long operatorId, Long id, TeamRequest request) {
        requireAdmin(operatorId);
        Team team = requireTeam(id);
        String name = request.getName().trim();
        if (!name.equals(team.getName())) {
            requireUniqueName(name, id);
            team.setName(name);
            teamMapper.updateById(team);
        }
        return toVO(team, memberCountMap());
    }

    /**
     * 删除团队：该团队下的用户 team_id 置空（不影响用户账号）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long operatorId, Long id) {
        requireAdmin(operatorId);
        requireTeam(id);
        teamMapper.deleteById(id);
        // 显式 SQL 置空，避免 MyBatis-Plus 对 null 字段的默认忽略
        userMapper.update(null, new LambdaUpdateWrapper<User>()
                .eq(User::getTeamId, id)
                .set(User::getTeamId, null));
    }

    /** 团队名唯一校验：重名抛 409（更新时排除自身） */
    private void requireUniqueName(String name, Long excludeId) {
        LambdaQueryWrapper<Team> wrapper = new LambdaQueryWrapper<Team>().eq(Team::getName, name);
        if (excludeId != null) {
            wrapper.ne(Team::getId, excludeId);
        }
        Long count = teamMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "team.name.exists");
        }
    }

    /** 校验操作者是否为系统管理员 */
    private void requireAdmin(Long operatorId) {
        if (!permissionService.isSystemAdmin(operatorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin.permissionDenied");
        }
    }

    private Team requireTeam(Long id) {
        Team team = teamMapper.selectById(id);
        if (team == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "team.notFound");
        }
        return team;
    }

    /** 统计各团队成员数（一次查询，避免 N+1） */
    private Map<Long, Long> memberCountMap() {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .select(User::getTeamId)
                .isNotNull(User::getTeamId));
        return users.stream().collect(Collectors.groupingBy(User::getTeamId, Collectors.counting()));
    }

    private static TeamVO toVO(Team team, Map<Long, Long> memberCount) {
        TeamVO vo = new TeamVO();
        BeanUtils.copyProperties(team, vo);
        vo.setMemberCount(team.getId() == null ? 0L : memberCount.getOrDefault(team.getId(), 0L));
        return vo;
    }
}
