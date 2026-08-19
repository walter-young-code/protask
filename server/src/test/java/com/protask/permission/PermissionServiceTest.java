package com.protask.permission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.entity.Project;
import com.protask.entity.ProjectMember;
import com.protask.entity.User;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PermissionService 角色-权限矩阵单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long PROJECT_ID = 100L;

    /** 分享凭证请求头（与 PermissionService 保持一致） */
    private static final String SHARE_TOKEN_HEADER = "X-Share-Token";

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private UserMapper userMapper;

    private PermissionService permissionService;
    private Project project;

    @BeforeEach
    void setUp() {
        permissionService = new PermissionService(projectMapper, projectMemberMapper, userMapper);
        project = new Project();
        project.setId(PROJECT_ID);
        project.setOwnerId(1L);
        project.setType("P");

        // 默认非系统管理员
        User user = new User();
        user.setId(USER_ID);
        user.setRole("user");
        lenient().when(userMapper.selectById(USER_ID)).thenReturn(user);
        lenient().when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        // 默认非成员
        lenient().when(projectMemberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 模拟当前请求携带分享凭证（null=不携带） */
    private void mockShareToken(String shareId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (shareId != null) {
            request.addHeader(SHARE_TOKEN_HEADER, shareId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void mockMemberRole(String role) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(PROJECT_ID);
        member.setUserId(USER_ID);
        member.setRole(role);
        when(projectMemberMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(member);
    }

    @Test
    void adminHasAllPermissions() {
        mockMemberRole("admin");
        for (ProjectPermission permission : ProjectPermission.values()) {
            assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, permission),
                    "admin should have " + permission);
        }
    }

    @Test
    void editorCanManageCardsButNotRoles() {
        mockMemberRole("editor");
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS));
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_PROPERTIES));
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.COMMENT_PROJECT_CARDS));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_ROLES));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.DELETE_PROJECT));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_TYPE));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.DELETE_OTHERS_COMMENTS));
    }

    @Test
    void commenterCanOnlyViewAndComment() {
        mockMemberRole("commenter");
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.COMMENT_PROJECT_CARDS));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS));
    }

    @Test
    void viewerCanOnlyView() {
        mockMemberRole("viewer");
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.COMMENT_PROJECT_CARDS));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS));
    }

    @Test
    void nonMemberOfPrivateProjectHasNoPermission() {
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
    }

    @Test
    void nonMemberOfOpenProjectWithoutShareTokenIsDenied() {
        project.setType("O");
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS));
    }

    @Test
    void nonMemberOfOpenProjectWithValidShareTokenCanOnlyView() {
        project.setType("O");
        project.setShareId("share-uuid-123");
        mockShareToken("share-uuid-123");
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.COMMENT_PROJECT_CARDS));
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS));
    }

    @Test
    void nonMemberOfOpenProjectWithInvalidShareTokenIsDenied() {
        project.setType("O");
        project.setShareId("share-uuid-123");
        mockShareToken("wrong-token");
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
    }

    @Test
    void oldShareTokenIsDeniedAfterSharingDisabled() {
        project.setType("O");
        // 分享已关闭：shareId 已清空，旧链接携带的凭证不再匹配
        mockShareToken("old-share-uuid");
        assertFalse(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT));
    }

    @Test
    void isShareVisitorOnlyTrueForMatchingToken() {
        project.setType("O");
        project.setShareId("share-uuid-123");
        mockShareToken("share-uuid-123");
        assertTrue(permissionService.isShareVisitor(PROJECT_ID));
        mockShareToken("wrong-token");
        assertFalse(permissionService.isShareVisitor(PROJECT_ID));
        mockShareToken(null);
        assertFalse(permissionService.isShareVisitor(PROJECT_ID));
    }

    @Test
    void memberWithShareTokenKeepsOwnRole() {
        project.setType("O");
        project.setShareId("share-uuid-123");
        mockMemberRole("editor");
        mockShareToken("share-uuid-123");
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS));
    }

    @Test
    void systemAdminBypassesAllChecks() {
        User admin = new User();
        admin.setId(USER_ID);
        admin.setRole("admin");
        when(userMapper.selectById(USER_ID)).thenReturn(admin);
        assertTrue(permissionService.hasPermissionToProject(USER_ID, PROJECT_ID, ProjectPermission.DELETE_PROJECT));
    }

    @Test
    void ownerIsTreatedAsAdmin() {
        when(userMapper.selectById(1L)).thenAnswer(inv -> {
            User u = new User();
            u.setId(1L);
            u.setRole("user");
            return u;
        });
        assertTrue(permissionService.hasPermissionToProject(1L, PROJECT_ID, ProjectPermission.DELETE_PROJECT));
    }
}
