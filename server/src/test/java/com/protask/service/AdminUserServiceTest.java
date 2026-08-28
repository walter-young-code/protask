package com.protask.service;

import com.protask.common.BusinessException;
import com.protask.dto.AdminUserCreateRequest;
import com.protask.dto.AdminUserQuery;
import com.protask.dto.AdminUserUpdateRequest;
import com.protask.dto.AdminUserVO;
import com.protask.dto.PageResult;
import com.protask.entity.Project;
import com.protask.entity.User;
import com.protask.mapper.NotificationMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.TeamMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdminUserService 单元测试：管理员校验与自我保护规则。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserServiceTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long OTHER_ID = 2L;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TeamMapper teamMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private PermissionService permissionService;

    @Mock
    private NotificationMapper notificationMapper;

    private AdminUserService adminUserService;

    @BeforeEach
    void setUp() {
        adminUserService = new AdminUserService(userMapper, teamMapper, projectMapper, projectMemberMapper,
                passwordEncoder, permissionService, notificationMapper);
        // lenient：非管理员拒绝测试不会使用该 stub
        lenient().when(permissionService.isSystemAdmin(ADMIN_ID)).thenReturn(true);
    }

    @Test
    void page_rejectsNonAdmin() {
        assertThrows(BusinessException.class,
                () -> adminUserService.page(OTHER_ID, query()));
    }

    @Test
    void create_successWithDefaults() {
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setUsername("alice");
        request.setPassword("Secret123!");
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("Secret123!")).thenReturn("encrypted");
        when(teamMapper.selectList(any())).thenReturn(java.util.List.of());

        AdminUserVO vo = adminUserService.create(ADMIN_ID, request);

        assertEquals("alice", vo.getUsername());
        assertEquals("user", vo.getRole());
        assertEquals(1, vo.getStatus());
        assertEquals("alice", vo.getNickname());
        verify(userMapper).insert(any(User.class));
    }

    @Test
    void create_rejectsDuplicateUsername() {
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setUsername("alice");
        request.setPassword("secret123");
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class, () -> adminUserService.create(ADMIN_ID, request));
    }

    @Test
    void create_rejectsInvalidRole() {
        AdminUserCreateRequest request = new AdminUserCreateRequest();
        request.setUsername("alice");
        request.setPassword("secret123");
        request.setRole("owner");
        when(userMapper.selectCount(any())).thenReturn(0L);

        assertThrows(BusinessException.class, () -> adminUserService.create(ADMIN_ID, request));
    }

    @Test
    void update_rejectsSelfRoleChange() {
        User self = user(ADMIN_ID, "admin", 1);
        when(userMapper.selectById(ADMIN_ID)).thenReturn(self);
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setRole("user");

        assertThrows(BusinessException.class, () -> adminUserService.update(ADMIN_ID, ADMIN_ID, request));
    }

    @Test
    void update_rejectsLastAdminDowngrade() {
        User target = user(OTHER_ID, "admin", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(userMapper.selectCount(any())).thenReturn(1L);
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setRole("user");

        assertThrows(BusinessException.class, () -> adminUserService.update(ADMIN_ID, OTHER_ID, request));
    }

    @Test
    void update_allowsDowngradeWhenAnotherAdminExists() {
        User target = user(OTHER_ID, "admin", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(userMapper.selectCount(any())).thenReturn(2L);
        when(teamMapper.selectList(any())).thenReturn(java.util.List.of());
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setRole("user");
        request.setNickname("new-name");

        AdminUserVO vo = adminUserService.update(ADMIN_ID, OTHER_ID, request);

        assertEquals("user", vo.getRole());
        assertEquals("new-name", vo.getNickname());
        verify(userMapper).updateById(any(User.class));
    }

    @Test
    void update_rejectsSelfDisable() {
        User self = user(ADMIN_ID, "admin", 1);
        when(userMapper.selectById(ADMIN_ID)).thenReturn(self);
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setStatus(0);

        assertThrows(BusinessException.class, () -> adminUserService.update(ADMIN_ID, ADMIN_ID, request));
    }

    @Test
    void update_throwsNotFoundForMissingUser() {
        when(userMapper.selectById(OTHER_ID)).thenReturn(null);
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();
        request.setNickname("n");

        assertThrows(BusinessException.class, () -> adminUserService.update(ADMIN_ID, OTHER_ID, request));
    }

    @Test
    void update_rejectsNonAdminOperator() {
        when(permissionService.isSystemAdmin(OTHER_ID)).thenReturn(false);
        AdminUserUpdateRequest request = new AdminUserUpdateRequest();

        assertThrows(BusinessException.class, () -> adminUserService.update(OTHER_ID, OTHER_ID, request));
    }

    @Test
    void resetPassword_encodesAndUpdates() {
        User target = user(OTHER_ID, "user", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(passwordEncoder.encode("New-Pass1")).thenReturn("encrypted-new");

        adminUserService.resetPassword(ADMIN_ID, OTHER_ID, "New-Pass1");

        assertEquals("encrypted-new", target.getPassword());
        verify(userMapper).updateById(target);
    }

    @Test
    void page_returnsConvertedRecords() {
        // selectPage 由 MyBatis-Plus 分页插件执行，此处仅验证 admin 校验放行路径
        when(userMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        when(teamMapper.selectList(any())).thenReturn(java.util.List.of());
        PageResult<AdminUserVO> result = adminUserService.page(ADMIN_ID, query());
        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    void deleteUser_rejectsSelfDeletion() {
        assertThrows(BusinessException.class, () -> adminUserService.deleteUser(ADMIN_ID, ADMIN_ID, null));
    }

    @Test
    void deleteUser_rejectsLastAdminDeletion() {
        User target = user(OTHER_ID, "admin", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class, () -> adminUserService.deleteUser(ADMIN_ID, OTHER_ID, null));
    }

    @Test
    void deleteUser_rejectsTakeoverSelf() {
        User target = user(OTHER_ID, "user", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);

        assertThrows(BusinessException.class,
                () -> adminUserService.deleteUser(ADMIN_ID, OTHER_ID, OTHER_ID));
    }

    @Test
    void deleteUser_rejectsInvalidTakeoverUser() {
        User target = user(OTHER_ID, "user", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        // 接管人不存在或已注销
        when(userMapper.selectById(99L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> adminUserService.deleteUser(ADMIN_ID, OTHER_ID, 99L));
    }

    @Test
    void deleteUser_transfersProjectsAndMarksDeleted() {
        User target = user(OTHER_ID, "user", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(passwordEncoder.encode(anyString())).thenReturn("random-password");
        Project project = new Project();
        project.setId(10L);
        project.setOwnerId(OTHER_ID);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        // 项目内无其他 admin 成员，负责人转移给执行注销的管理员
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());

        adminUserService.deleteUser(ADMIN_ID, OTHER_ID, null);

        assertEquals(ADMIN_ID, project.getOwnerId());
        assertEquals(0, target.getStatus());
        assertNotNull(target.getDeletedAt());
        assertEquals(1, target.getPasswordVersion());
        assertEquals("random-password", target.getPassword());
        verify(projectMemberMapper).delete(any());
        // 注销时清空该用户的通知库
        verify(notificationMapper).delete(any());
        verify(userMapper).updateById(target);
    }

    @Test
    void deleteUser_transfersToSpecifiedTakeoverUser() {
        User target = user(OTHER_ID, "user", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(userMapper.selectById(5L)).thenReturn(user(5L, "user", 1));
        when(passwordEncoder.encode(anyString())).thenReturn("random-password");
        Project project = new Project();
        project.setId(10L);
        project.setOwnerId(OTHER_ID);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));

        // 手动指定接管人 5L：即使项目内存在 admin 成员也全部转给指定接管人
        adminUserService.deleteUser(ADMIN_ID, OTHER_ID, 5L);

        assertEquals(5L, project.getOwnerId());
        verify(projectMemberMapper, never()).selectList(any());
    }

    @Test
    void deleteUser_transfersToProjectAdminMemberWhenExists() {
        User target = user(OTHER_ID, "user", 1);
        when(userMapper.selectById(OTHER_ID)).thenReturn(target);
        when(passwordEncoder.encode(anyString())).thenReturn("random-password");
        Project project = new Project();
        project.setId(10L);
        project.setOwnerId(OTHER_ID);
        when(projectMapper.selectList(any())).thenReturn(List.of(project));
        com.protask.entity.ProjectMember admin = new com.protask.entity.ProjectMember();
        admin.setUserId(3L);
        admin.setRole("admin");
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(admin));

        adminUserService.deleteUser(ADMIN_ID, OTHER_ID, null);

        assertEquals(3L, project.getOwnerId());
    }

    private AdminUserQuery query() {
        AdminUserQuery query = new AdminUserQuery();
        query.setPage(1L);
        query.setSize(10L);
        return query;
    }

    private User user(Long id, String role, Integer status) {
        User user = new User();
        user.setId(id);
        user.setUsername("user" + id);
        user.setRole(role);
        user.setStatus(status);
        return user;
    }
}
