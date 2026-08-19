package com.protask.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.protask.common.BusinessException;
import com.protask.dto.PageResult;
import com.protask.dto.TeamRequest;
import com.protask.dto.TeamVO;
import com.protask.entity.Team;
import com.protask.entity.User;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TeamService 单元测试：管理员校验与团队增删改查。
 */
@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long OTHER_ID = 2L;
    private static final Long TEAM_ID = 10L;

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PermissionService permissionService;

    private TeamService teamService;

    /** 纯 Mockito 环境缺少 MyBatis-Plus 容器，需手动初始化实体元数据供 lambda 列解析使用 */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), User.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Team.class);
    }

    @BeforeEach
    void setUp() {
        teamService = new TeamService(teamMapper, userMapper, permissionService);
        lenient().when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(true);
    }

    @Test
    void page_rejectsNonAdmin() {
        assertThrows(BusinessException.class,
                () -> teamService.page(OTHER_ID, null, 1L, 10L));
    }

    @Test
    void page_returnsConvertedRecords() {
        when(teamMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        when(userMapper.selectList(any())).thenReturn(List.of());
        PageResult<TeamVO> result = teamService.page(ADMIN_ID, null, 1L, 10L);
        assertEquals(0, result.getTotal());
    }

    @Test
    void create_successWithTrimmedName() {
        TeamRequest request = new TeamRequest();
        request.setName("  研发团队  ");
        when(teamMapper.selectCount(any())).thenReturn(0L);

        TeamVO vo = teamService.create(ADMIN_ID, request);

        assertEquals("研发团队", vo.getName());
        verify(teamMapper).insert(any(Team.class));
    }

    @Test
    void create_rejectsDuplicateName() {
        TeamRequest request = new TeamRequest();
        request.setName("研发团队");
        when(teamMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class, () -> teamService.create(ADMIN_ID, request));
    }

    @Test
    void update_rejectsDuplicateNameExcludingSelf() {
        Team team = team(1L, "研发团队");
        when(teamMapper.selectById(1L)).thenReturn(team);
        when(teamMapper.selectCount(any())).thenReturn(1L);
        TeamRequest request = new TeamRequest();
        request.setName("产品团队");

        assertThrows(BusinessException.class, () -> teamService.update(ADMIN_ID, 1L, request));
    }

    @Test
    void update_keepsNameWhenUnchanged() {
        Team team = team(TEAM_ID, "研发团队");
        when(teamMapper.selectById(TEAM_ID)).thenReturn(team);
        when(userMapper.selectList(any())).thenReturn(List.of());
        TeamRequest request = new TeamRequest();
        request.setName("研发团队");

        TeamVO vo = teamService.update(ADMIN_ID, TEAM_ID, request);

        assertEquals("研发团队", vo.getName());
        verify(teamMapper, never()).updateById(any(Team.class));
    }

    @Test
    void delete_clearsUserTeamId() {
        Team team = team(TEAM_ID, "研发团队");
        when(teamMapper.selectById(TEAM_ID)).thenReturn(team);

        teamService.delete(ADMIN_ID, TEAM_ID);

        verify(teamMapper).deleteById(TEAM_ID);
        verify(userMapper).update(any(), any());
    }

    @Test
    void delete_throwsNotFound() {
        when(teamMapper.selectById(TEAM_ID)).thenReturn(null);
        assertThrows(BusinessException.class, () -> teamService.delete(ADMIN_ID, TEAM_ID));
    }

    @Test
    void delete_rejectsNonAdmin() {
        assertThrows(BusinessException.class, () -> teamService.delete(OTHER_ID, TEAM_ID));
    }

    private Team team(Long id, String name) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        return team;
    }
}
