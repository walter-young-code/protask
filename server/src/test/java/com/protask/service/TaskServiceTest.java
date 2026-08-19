package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.ProjectVO;
import com.protask.dto.TaskRequest;
import com.protask.dto.TaskVO;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TaskService 单元测试：任务创建/更新仅处理动态属性（整体替换语义）。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long TASK_ID = 10L;
    private static final Long PROJECT_ID = 100L;

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PermissionService permissionService;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(blockMapper, projectMapper, userMapper, permissionService);
    }

    private Project existingProject() {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        return project;
    }

    private Block cardWithProperties(Map<String, Object> properties) {
        Block card = new Block();
        card.setId(TASK_ID);
        card.setProjectId(PROJECT_ID);
        card.setType(BlockTypes.CARD);
        card.setTitle("任务");
        card.setCreatedBy(USER_ID);
        card.setModifiedBy(USER_ID);
        Map<String, Object> fields = new HashMap<>();
        fields.put("description", "");
        fields.put("properties", new HashMap<>(properties));
        card.setFields(fields);
        return card;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertiesOf(Block card) {
        return (Map<String, Object>) card.getFields().get("properties");
    }

    @Test
    void createTaskStoresOnlyRequestProperties() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(existingProject());
        User creator = new User();
        creator.setId(USER_ID);
        creator.setNickname("张三");
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(creator));

        TaskRequest request = new TaskRequest();
        request.setTitle("新任务");
        request.setProperties(Map.of("p1", "o1"));
        TaskVO vo = taskService.createTask(USER_ID, PROJECT_ID, request);

        ArgumentCaptor<Block> captor = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper).insert(captor.capture());
        Map<String, Object> saved = propertiesOf(captor.getValue());
        assertEquals(Map.of("p1", "o1"), saved);
        assertFalse(saved.containsKey("status"));
        assertFalse(saved.containsKey("priority"));
        assertEquals(Map.of("p1", "o1"), vo.getProperties());
        assertEquals("张三", vo.getCreatorName());
    }

    @Test
    void createTaskWithoutPropertiesUsesEmptyMap() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(existingProject());

        TaskRequest request = new TaskRequest();
        request.setTitle("新任务");
        taskService.createTask(USER_ID, PROJECT_ID, request);

        ArgumentCaptor<Block> captor = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper).insert(captor.capture());
        assertTrue(propertiesOf(captor.getValue()).isEmpty());
    }

    @Test
    void updateTaskReplacesPropertiesEntirely() {
        when(blockMapper.selectById(TASK_ID)).thenReturn(cardWithProperties(Map.of("p1", "o1")));

        TaskRequest request = new TaskRequest();
        request.setTitle("任务");
        request.setProperties(Map.of("p2", 42));
        taskService.updateTask(USER_ID, TASK_ID, request);

        ArgumentCaptor<Block> captor = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper).updateById(captor.capture());
        Map<String, Object> saved = propertiesOf(captor.getValue());
        assertEquals(Map.of("p2", 42), saved);
        assertFalse(saved.containsKey("p1"));
    }

    @Test
    void updateTaskWithoutPropertiesClearsAll() {
        when(blockMapper.selectById(TASK_ID)).thenReturn(cardWithProperties(Map.of("p1", "o1")));

        TaskRequest request = new TaskRequest();
        request.setTitle("任务");
        taskService.updateTask(USER_ID, TASK_ID, request);

        ArgumentCaptor<Block> captor = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper).updateById(captor.capture());
        assertTrue(propertiesOf(captor.getValue()).isEmpty());
    }

    @Test
    void deletedCardIsNotFound() {
        Block card = cardWithProperties(Map.of());
        card.setDeleteAt(java.time.LocalDateTime.now());
        when(blockMapper.selectById(TASK_ID)).thenReturn(card);
        TaskRequest request = new TaskRequest();
        request.setTitle("任务");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> taskService.updateTask(USER_ID, TASK_ID, request));
        assertEquals("task.notFound", ex.getMessageKey());
    }

    @Test
    void nonCardBlockIsNotFound() {
        Block card = cardWithProperties(Map.of());
        card.setType(BlockTypes.TEXT);
        when(blockMapper.selectById(TASK_ID)).thenReturn(card);
        TaskRequest request = new TaskRequest();
        request.setTitle("任务");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> taskService.updateTask(USER_ID, TASK_ID, request));
        assertEquals("task.notFound", ex.getMessageKey());
    }

    @Test
    void searchWithBlankKeywordReturnsEmpty() {
        assertTrue(taskService.searchTasks(USER_ID, "  ").isEmpty());
        verifyNoInteractions(blockMapper);
    }

    @Test
    void searchWithEmptyScopeSkipsCardQuery() {
        User user = new User();
        user.setId(USER_ID);
        user.setRole("user");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(projectMapper.selectMyProjects(USER_ID)).thenReturn(List.of());

        // 无任何可搜索项目时直接返回空，不执行卡片查询
        assertTrue(taskService.searchTasks(USER_ID, "任务").isEmpty());
        verifyNoInteractions(blockMapper);
    }

    @Test
    void searchReturnsCardsWithProjectName() {
        User user = new User();
        user.setId(USER_ID);
        user.setRole("user");
        user.setNickname("张三");
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        ProjectVO memberProject = new ProjectVO();
        memberProject.setId(PROJECT_ID);
        when(projectMapper.selectMyProjects(USER_ID)).thenReturn(List.of(memberProject));

        when(blockMapper.searchCards(eq("任务"), anyList(), anyInt()))
                .thenReturn(List.of(cardWithProperties(Map.of("p1", "o1"))));
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user));

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        when(projectMapper.selectBatchIds(any())).thenReturn(List.of(project));

        List<TaskVO> result = taskService.searchTasks(USER_ID, "任务");
        assertEquals(1, result.size());
        assertEquals("示例项目", result.get(0).getProjectName());
        assertEquals("张三", result.get(0).getCreatorName());
        assertEquals(Map.of("p1", "o1"), result.get(0).getProperties());
    }
}
