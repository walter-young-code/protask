package com.protask.service;

import com.protask.common.BusinessException;
import com.protask.common.MessageUtil;
import com.protask.dto.CardPropertiesRequest;
import com.protask.dto.ProjectCopyRequest;
import com.protask.dto.ProjectRequest;
import com.protask.dto.ProjectTemplateRequest;
import com.protask.dto.ProjectTemplateVO;
import com.protask.dto.ProjectToTemplateRequest;
import com.protask.dto.ProjectVO;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.ProjectMember;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.NotificationMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.ProjectCategoryMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import com.protask.permission.ProjectRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectService 单元测试：属性模板整体替换语义与级联清理。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long PROJECT_ID = 100L;

    /** 历史遗留模板：含四个原内置属性定义，用于验证整体替换后不再保留 */
    private static final String LEGACY_TEMPLATE = """
            [{"id":"status","name":"状态","type":"select","options":[\
            {"id":"todo","value":"todo","color":"blue"},\
            {"id":"in_progress","value":"in_progress","color":"orange"},\
            {"id":"done","value":"done","color":"green"}]},\
            {"id":"priority","name":"优先级","type":"select","options":[\
            {"id":"low","value":"low","color":"gray"},\
            {"id":"medium","value":"medium","color":"blue"},\
            {"id":"high","value":"high","color":"orange"},\
            {"id":"urgent","value":"urgent","color":"red"}]},\
            {"id":"assignee","name":"负责人","type":"text"},\
            {"id":"dueDate","name":"截止时间","type":"date"}]""";

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private ProjectCategoryMapper projectCategoryMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PermissionService permissionService;

    @Mock
    private BlockService blockService;

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private NotificationMapper notificationMapper;

    private ProjectService projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectService(
                projectMapper, projectMemberMapper, projectCategoryMapper, blockMapper, userMapper,
                permissionService, blockService, attachmentService, notificationMapper);
    }

    private Project projectWithTemplate(String template) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        project.setCardProperties(template);
        project.setStatus("active");
        return project;
    }

    private CardPropertiesRequest.CardPropertyDef def(String id, String name, String type) {
        CardPropertiesRequest.CardPropertyDef def = new CardPropertiesRequest.CardPropertyDef();
        def.setId(id);
        def.setName(name);
        def.setType(type);
        return def;
    }

    private CardPropertiesRequest.CardPropertyOption option(String id, String value) {
        CardPropertiesRequest.CardPropertyOption option = new CardPropertiesRequest.CardPropertyOption();
        option.setId(id);
        option.setValue(value);
        return option;
    }

    /** updateCardProperties 末尾 getProject 需要的通用打桩 */
    private void stubReload() {
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);
    }

    @Test
    void newProjectTemplateIsEmpty() {
        ProjectRequest request = new ProjectRequest();
        request.setName("新项目");
        Project[] inserted = new Project[1];
        doAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(PROJECT_ID);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(PROJECT_ID)).thenAnswer(invocation -> inserted[0]);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);

        ProjectVO vo = projectService.createProject(USER_ID, request);

        assertEquals("[]", inserted[0].getCardProperties());
        assertEquals("[]", vo.getCardProperties());
    }

    @Test
    void replaceTemplateKeepsOnlyRequestedProperties() {
        Project project = projectWithTemplate(LEGACY_TEMPLATE);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        stubReload();

        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(def("p1", "工作量", "number")));
        projectService.updateCardProperties(USER_ID, PROJECT_ID, request);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        String saved = captor.getValue().getCardProperties();
        assertTrue(saved.contains("\"id\":\"p1\""));
        assertFalse(saved.contains("\"id\":\"status\""));
        assertFalse(saved.contains("\"id\":\"assignee\""));
        // 四个原内置属性被整体替换删除，触发卡片级联清理
        verify(blockService).removePropertyFromCards(eq(PROJECT_ID),
                eq(List.of("status", "priority", "assignee", "dueDate")), eq(USER_ID));
    }

    @Test
    void removedCustomPropertyTriggersCardCleanup() {
        String template = "[{\"id\":\"p1\",\"name\":\"工作量\",\"type\":\"number\"},"
                + "{\"id\":\"p2\",\"name\":\"环境\",\"type\":\"text\"}]";
        Project project = projectWithTemplate(template);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        stubReload();

        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(def("p1", "工作量", "number")));
        projectService.updateCardProperties(USER_ID, PROJECT_ID, request);

        verify(blockService).removePropertyFromCards(eq(PROJECT_ID), eq(List.of("p2")), eq(USER_ID));
    }

    @Test
    void unchangedTemplateSkipsCleanup() {
        String template = "[{\"id\":\"p1\",\"name\":\"工作量\",\"type\":\"number\"}]";
        Project project = projectWithTemplate(template);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        stubReload();

        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(def("p1", "工作量", "number")));
        projectService.updateCardProperties(USER_ID, PROJECT_ID, request);

        verify(blockService, never()).removePropertyFromCards(any(), any(), any());
    }

    @Test
    void formerBuiltinIdNowAllowed() {
        Project project = projectWithTemplate("[]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        stubReload();

        CardPropertiesRequest.CardPropertyDef prop = def("status", "阶段", "select");
        prop.setOptions(List.of(option("o1", "待办"), option("o2", "完成")));
        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(prop));
        ProjectVO vo = projectService.updateCardProperties(USER_ID, PROJECT_ID, request);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getCardProperties().contains("\"id\":\"status\""));
        assertTrue(vo.getCardProperties().contains("status"));
    }

    @Test
    void duplicateIdsRejected() {
        Project project = projectWithTemplate("[]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(def("p1", "甲", "text"), def("p1", "乙", "text")));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateCardProperties(USER_ID, PROJECT_ID, request));
        assertEquals("project.propertyIdDuplicate", ex.getMessageKey());
    }

    @Test
    void selectWithoutOptionsRejected() {
        Project project = projectWithTemplate("[]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(def("p1", "环境", "select")));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateCardProperties(USER_ID, PROJECT_ID, request));
        assertEquals("project.propertyOptionsRequired", ex.getMessageKey());
    }

    @Test
    void selectWithDuplicateOptionIdsRejected() {
        Project project = projectWithTemplate("[]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);

        CardPropertiesRequest.CardPropertyDef prop = def("p1", "环境", "select");
        prop.setOptions(List.of(option("o1", "测试"), option("o1", "预发")));
        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of(prop));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateCardProperties(USER_ID, PROJECT_ID, request));
        assertEquals("project.propertyOptionDuplicate", ex.getMessageKey());
    }

    @Test
    void requiresManagePropertiesPermission() {
        Project project = projectWithTemplate("[]");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        org.mockito.Mockito.doThrow(new BusinessException(
                        com.protask.common.ResultCode.FORBIDDEN, "project.permissionDenied"))
                .when(permissionService)
                .checkPermission(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_PROPERTIES);

        CardPropertiesRequest request = new CardPropertiesRequest();
        request.setProperties(List.of());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateCardProperties(USER_ID, PROJECT_ID, request));
        assertEquals("project.permissionDenied", ex.getMessageKey());
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    // ---------- 项目模板库 ----------

    @Test
    void listTemplatesReturnsTemplatesWithSampleCards() {
        Project template = new Project();
        template.setId(1L);
        template.setName("销售管道 CRM");
        template.setIcon("📈");
        template.setCardProperties("[]");
        template.setIsTemplate(true);
        when(projectMapper.selectList(any())).thenReturn(List.of(template));
        Block card = new Block();
        card.setProjectId(1L);
        card.setTitle("官网注册客户跟进");
        card.setFields(Map.of("icon", "📇", "properties", Map.of("stage", "following")));
        // 批量查询模板示例卡片（listTemplates 已不再逐模板调用 listCards）
        when(blockMapper.selectList(any())).thenReturn(List.of(card));

        List<ProjectTemplateVO> vos = projectService.listTemplates();

        assertEquals(1, vos.size());
        assertEquals("销售管道 CRM", vos.get(0).getName());
        assertEquals("📈", vos.get(0).getIcon());
        assertEquals(1, vos.get(0).getSampleCards().size());
        assertEquals("官网注册客户跟进", vos.get(0).getSampleCards().get(0).getTitle());
        assertEquals("📇", vos.get(0).getSampleCards().get(0).getIcon());
        assertEquals("following", vos.get(0).getSampleCards().get(0).getProperties().get("stage"));
    }

    @Test
    void createProjectFromTemplateCopiesBlocksAndAddsOwnerAsAdmin() {
        Project template = new Project();
        template.setId(1L);
        template.setName("销售管道 CRM");
        template.setDescription("管理销售线索");
        template.setIcon("📈");
        template.setCardProperties("[{\"id\":\"stage\",\"name\":\"阶段\",\"type\":\"select\"}]");
        template.setIsTemplate(true);
        when(projectMapper.selectById(1L)).thenReturn(template);

        Project[] inserted = new Project[1];
        doAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(200L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(200L)).thenAnswer(invocation -> inserted[0]);
        when(permissionService.getEffectiveRole(USER_ID, 200L)).thenReturn(ProjectRole.ADMIN);
        User owner = new User();
        owner.setId(USER_ID);
        owner.setNickname("测试");
        when(userMapper.selectById(USER_ID)).thenReturn(owner);

        ProjectTemplateRequest request = new ProjectTemplateRequest();
        request.setTemplateId(1L);
        request.setName("我的销售管道");
        ProjectVO vo = projectService.createProjectFromTemplate(USER_ID, request);

        assertEquals("我的销售管道", inserted[0].getName());
        assertEquals("管理销售线索", inserted[0].getDescription());
        assertFalse(inserted[0].getIsTemplate());
        assertEquals(template.getCardProperties(), inserted[0].getCardProperties());
        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(memberCaptor.capture());
        assertEquals(200L, memberCaptor.getValue().getProjectId());
        assertEquals(USER_ID, memberCaptor.getValue().getUserId());
        assertEquals(ProjectRole.ADMIN.getValue(), memberCaptor.getValue().getRole());
        verify(blockService).copyBlocksFromProject(1L, 200L, USER_ID);
        assertEquals(200L, vo.getId());
    }

    @Test
    void templateProjectNotAccessibleAsNormalProject() {
        Project template = new Project();
        template.setId(1L);
        template.setName("模板");
        template.setIsTemplate(true);
        when(projectMapper.selectById(1L)).thenReturn(template);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getProject(USER_ID, 1L));
        assertEquals("project.notFound", ex.getMessageKey());
    }

    @Test
    void createFromTemplateRejectsNonTemplateProject() {
        Project normal = new Project();
        normal.setId(1L);
        normal.setName("普通项目");
        normal.setIsTemplate(false);
        when(projectMapper.selectById(1L)).thenReturn(normal);

        ProjectTemplateRequest request = new ProjectTemplateRequest();
        request.setTemplateId(1L);
        request.setName("新项目");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProjectFromTemplate(USER_ID, request));
        assertEquals("template.notFound", ex.getMessageKey());
        verify(projectMapper, never()).insert(any(Project.class));
    }

    // ---------- 现有项目转为模板 ----------

    @Test
    void convertProjectToTemplateCopiesProjectAsTemplate() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("客户管理系统");
        project.setDescription("管理客户信息");
        project.setIcon("🏢");
        project.setType("P");
        project.setCardProperties("[{\"id\":\"p1\",\"name\":\"优先级\",\"type\":\"select\"}]");
        project.setOwnerId(PROJECT_ID);
        project.setIsTemplate(false);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);

        Project[] inserted = new Project[1];
        doAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(300L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        Block card = new Block();
        card.setTitle("示例任务");
        card.setFields(Map.of("properties", Map.of("p1", "high")));
        when(blockService.listCards(300L)).thenReturn(List.of(card));

        ProjectTemplateVO vo = projectService.convertProjectToTemplate(USER_ID, PROJECT_ID, null);

        assertTrue(inserted[0].getIsTemplate());
        assertEquals("客户管理系统", inserted[0].getName());
        assertEquals("O", inserted[0].getType());
        assertEquals(project.getCardProperties(), inserted[0].getCardProperties());
        assertEquals(PROJECT_ID, inserted[0].getOwnerId());
        verify(blockService).copyBlocksFromProject(PROJECT_ID, 300L, USER_ID);
        assertEquals(300L, vo.getId());
        assertEquals(1, vo.getSampleCards().size());
        // 原项目仍为普通项目（未被标记为模板）
        assertFalse(project.getIsTemplate());
    }

    @Test
    void convertProjectToTemplateUsesCustomNameWhenProvided() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("客户管理系统");
        project.setCardProperties("[]");
        project.setIsTemplate(false);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);
        Project[] inserted = new Project[1];
        doAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(300L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(blockService.listCards(300L)).thenReturn(List.of());

        ProjectToTemplateRequest request = new ProjectToTemplateRequest();
        request.setName("客户管理模板");
        request.setDescription("模板描述");
        projectService.convertProjectToTemplate(USER_ID, PROJECT_ID, request);

        assertEquals("客户管理模板", inserted[0].getName());
        assertEquals("模板描述", inserted[0].getDescription());
    }

    @Test
    void convertProjectToTemplateRequiresAdmin() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("客户管理系统");
        project.setIsTemplate(false);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.VIEWER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.convertProjectToTemplate(USER_ID, PROJECT_ID, null));
        assertEquals("project.permissionDenied", ex.getMessageKey());
        verify(projectMapper, never()).insert(any(Project.class));
    }

    @Test
    void convertProjectToTemplateRejectsTemplateProject() {
        Project template = new Project();
        template.setId(1L);
        template.setName("已有模板");
        template.setIsTemplate(true);
        when(projectMapper.selectById(1L)).thenReturn(template);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.convertProjectToTemplate(USER_ID, 1L, null));
        assertEquals("project.notFound", ex.getMessageKey());
        verify(projectMapper, never()).insert(any(Project.class));
    }

    // ---------- 复制项目 ----------

    private Project sourceProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("客户管理系统");
        project.setDescription("管理客户信息");
        project.setType("P");
        project.setIcon("🏢");
        project.setCategoryId(7L);
        project.setCardProperties("[{\"id\":\"p1\",\"name\":\"优先级\",\"type\":\"select\"}]");
        project.setProperties("{\"defaultViewId\":5}");
        project.setOwnerId(9L);
        project.setIsTemplate(false);
        return project;
    }

    /** 复制项目成功路径共用的打桩：insert 返回 400L，getProject 依赖查询可命中 */
    private Project[] stubCopyInsert() {
        Project[] inserted = new Project[1];
        doAnswer(invocation -> {
            inserted[0] = invocation.getArgument(0);
            inserted[0].setId(400L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));
        when(projectMapper.selectById(400L)).thenAnswer(invocation -> inserted[0]);
        when(permissionService.getEffectiveRole(USER_ID, 400L)).thenReturn(ProjectRole.ADMIN);
        User operator = new User();
        operator.setId(USER_ID);
        operator.setNickname("复制者");
        when(userMapper.selectById(USER_ID)).thenReturn(operator);
        return inserted;
    }

    @Test
    void copyProjectClonesDataAndMakesOperatorOwner() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);
        when(blockService.copyBlocksFromProject(PROJECT_ID, 400L, USER_ID)).thenReturn(Map.of(5L, 55L));
        Project[] inserted = stubCopyInsert();

        ProjectCopyRequest request = new ProjectCopyRequest();
        request.setName("客户系统副本");
        ProjectVO vo = projectService.copyProject(USER_ID, PROJECT_ID, request);

        Project copy = inserted[0];
        assertEquals("客户系统副本", copy.getName());
        assertEquals("管理客户信息", copy.getDescription());
        assertEquals("P", copy.getType());
        assertEquals("🏢", copy.getIcon());
        assertEquals(7L, copy.getCategoryId());
        assertEquals(sourceProject().getCardProperties(), copy.getCardProperties());
        assertEquals(USER_ID, copy.getOwnerId());
        assertEquals(USER_ID, copy.getCreatedBy());
        assertFalse(copy.getIsTemplate());
        ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
        verify(projectMemberMapper).insert(memberCaptor.capture());
        assertEquals(400L, memberCaptor.getValue().getProjectId());
        assertEquals(USER_ID, memberCaptor.getValue().getUserId());
        assertEquals(ProjectRole.ADMIN.getValue(), memberCaptor.getValue().getRole());
        verify(blockService).copyBlocksFromProject(PROJECT_ID, 400L, USER_ID);
        // 仅复制者成为新项目成员，原负责人不复制
        verify(projectMemberMapper, never())
                .insert(org.mockito.ArgumentMatchers.<ProjectMember>argThat(
                        m -> !USER_ID.equals(m.getUserId())));
        assertEquals(400L, vo.getId());
    }

    @Test
    void copyProjectDefaultsNameWithSuffix() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);
        when(blockService.copyBlocksFromProject(PROJECT_ID, 400L, USER_ID)).thenReturn(Map.of(5L, 55L));
        Project[] inserted = stubCopyInsert();

        try (MockedStatic<MessageUtil> mocked = mockStatic(MessageUtil.class)) {
            mocked.when(() -> MessageUtil.get("project.copySuffix")).thenReturn(" 副本");
            projectService.copyProject(USER_ID, PROJECT_ID, null);
        }

        assertEquals("客户管理系统 副本", inserted[0].getName());
    }

    @Test
    void copyProjectRemapsDefaultViewToCopiedView() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);
        when(blockService.copyBlocksFromProject(PROJECT_ID, 400L, USER_ID)).thenReturn(Map.of(5L, 55L));
        stubCopyInsert();

        projectService.copyProject(USER_ID, PROJECT_ID, null);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getProperties().contains("\"defaultViewId\":55"));
    }

    @Test
    void copyProjectRemovesUnmappedDefaultView() {
        Project project = sourceProject();
        project.setProperties("{\"defaultViewId\":999}");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);
        when(blockService.copyBlocksFromProject(PROJECT_ID, 400L, USER_ID)).thenReturn(Map.of());
        stubCopyInsert();

        projectService.copyProject(USER_ID, PROJECT_ID, null);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertFalse(captor.getValue().getProperties().contains("defaultViewId"));
    }

    @Test
    void copyProjectRequiresAdmin() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.VIEWER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.copyProject(USER_ID, PROJECT_ID, null));
        assertEquals("project.permissionDenied", ex.getMessageKey());
        verify(projectMapper, never()).insert(any(Project.class));
    }

    @Test
    void copyProjectRejectsTemplateProject() {
        Project template = new Project();
        template.setId(1L);
        template.setName("已有模板");
        template.setIsTemplate(true);
        when(projectMapper.selectById(1L)).thenReturn(template);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.copyProject(USER_ID, 1L, null));
        assertEquals("project.notFound", ex.getMessageKey());
        verify(projectMapper, never()).insert(any(Project.class));
    }

    // ---------- 项目归档 ----------

    /** 归档项目：项目管理员归档后状态变 archived 并关闭分享 */
    @Test
    void archiveProjectSetsStatusAndClearsShare() {
        Project project = sourceProject();
        project.setShareId("share-abc");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);

        projectService.archiveProject(USER_ID, PROJECT_ID);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertEquals("archived", captor.getValue().getStatus());
        assertEquals(null, captor.getValue().getShareId());
        assertEquals(USER_ID, captor.getValue().getModifiedBy());
    }

    /** 归档项目：非项目管理员拒绝 */
    @Test
    void archiveProjectRequiresAdmin() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.EDITOR);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.archiveProject(USER_ID, PROJECT_ID));
        assertEquals("project.permissionDenied", ex.getMessageKey());
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    /** 归档项目：已归档项目重复归档拒绝 */
    @Test
    void archiveProjectRejectsAlreadyArchived() {
        Project project = sourceProject();
        project.setStatus("archived");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.archiveProject(USER_ID, PROJECT_ID));
        assertEquals("project.alreadyArchived", ex.getMessageKey());
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    /** 恢复归档项目：系统管理员恢复后状态变回 active */
    @Test
    void restoreProjectReactivatesArchivedProject() {
        Project project = sourceProject();
        project.setStatus("archived");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.isSystemAdmin(USER_ID)).thenReturn(true);
        when(permissionService.getEffectiveRole(USER_ID, PROJECT_ID)).thenReturn(ProjectRole.ADMIN);

        projectService.restoreProject(USER_ID, PROJECT_ID);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertEquals("active", captor.getValue().getStatus());
        assertEquals(USER_ID, captor.getValue().getModifiedBy());
    }

    /** 恢复归档项目：非系统管理员拒绝 */
    @Test
    void restoreProjectRequiresSystemAdmin() {
        when(permissionService.isSystemAdmin(USER_ID)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.restoreProject(USER_ID, PROJECT_ID));
        assertEquals("admin.permissionDenied", ex.getMessageKey());
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    /** 恢复归档项目：未归档项目拒绝 */
    @Test
    void restoreProjectRejectsActiveProject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.isSystemAdmin(USER_ID)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.restoreProject(USER_ID, PROJECT_ID));
        assertEquals("project.notArchived", ex.getMessageKey());
        verify(projectMapper, never()).updateById(any(Project.class));
    }

    /** 管理后台删除归档项目：软删除项目并级联软删除全部块 */
    @Test
    void deleteArchivedProjectSoftDeletesProjectAndBlocks() {
        Project project = sourceProject();
        project.setStatus("archived");
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
        when(permissionService.isSystemAdmin(USER_ID)).thenReturn(true);

        projectService.deleteArchivedProject(USER_ID, PROJECT_ID);

        verify(blockService).softDeleteByProject(PROJECT_ID, USER_ID);
        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getDeleteAt() != null);
    }

    /** 管理后台删除归档项目：未归档项目拒绝 */
    @Test
    void deleteArchivedProjectRejectsActiveProject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(sourceProject());
        when(permissionService.isSystemAdmin(USER_ID)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.deleteArchivedProject(USER_ID, PROJECT_ID));
        assertEquals("project.notArchived", ex.getMessageKey());
        verify(blockService, never()).softDeleteByProject(any(), any());
        verify(projectMapper, never()).updateById(any(Project.class));
    }
}
