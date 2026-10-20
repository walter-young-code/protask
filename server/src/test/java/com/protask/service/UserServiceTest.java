package com.protask.service;

import com.protask.dto.UserVO;
import com.protask.entity.Team;
import com.protask.entity.User;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * UserService 单元测试：用户目录脱敏（非系统管理员不得获取 email/role）。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long NORMAL_USER_ID = 9L;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private PermissionService permissionService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userMapper, teamMapper, permissionService);
    }

    private User sampleUser() {
        User user = new User();
        user.setId(2L);
        user.setUsername("bob");
        user.setNickname("Bob");
        user.setFirstName("Bo");
        user.setLastName("B");
        user.setEmail("bob@example.com");
        user.setAvatar("avatar.png");
        user.setRole("user");
        user.setTeamId(3L);
        user.setStatus(1);
        return user;
    }

    private void stubUsersAndTeam() {
        when(userMapper.selectList(any())).thenReturn(List.of(sampleUser()));
        Team team = new Team();
        team.setId(3L);
        team.setName("研发团队");
        when(teamMapper.selectList(any())).thenReturn(List.of(team));
    }

    @Test
    void listActiveUsersHidesSensitiveFieldsForNonAdmin() {
        stubUsersAndTeam();
        when(permissionService.isSystemAdmin(NORMAL_USER_ID)).thenReturn(false);

        List<UserVO> users = userService.listActiveUsers(NORMAL_USER_ID);

        assertEquals(1, users.size());
        UserVO vo = users.get(0);
        assertEquals("bob", vo.getUsername());
        assertEquals("Bob", vo.getNickname());
        assertEquals("Bo B", vo.getFullName());
        assertEquals("avatar.png", vo.getAvatar());
        assertEquals("研发团队", vo.getTeamName());
        // 敏感字段不返回
        assertNull(vo.getEmail());
        assertNull(vo.getRole());
    }

    @Test
    void listActiveUsersKeepsSensitiveFieldsForSystemAdmin() {
        stubUsersAndTeam();
        when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(true);

        List<UserVO> users = userService.listActiveUsers(ADMIN_ID);

        assertEquals(1, users.size());
        UserVO vo = users.get(0);
        assertEquals("bob@example.com", vo.getEmail());
        assertEquals("user", vo.getRole());
        assertEquals("研发团队", vo.getTeamName());
    }
}
