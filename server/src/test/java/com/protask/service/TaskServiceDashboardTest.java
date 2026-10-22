package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.dto.DashboardVO;
import com.protask.dto.ProjectVO;
import com.protask.entity.Block;
import com.protask.entity.User;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TaskService.getDashboard 单元测试：统计口径基于内置属性 id 约定。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceDashboardTest {

    private static final Long USER_ID = 2L;
    private static final Long OTHER_USER_ID = 3L;
    private static final Long PROJECT_ID = 100L;
    private static final Long PLAIN_PROJECT_ID = 200L;

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private AttachmentMapper attachmentMapper;

    @Mock
    private PermissionService permissionService;

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(blockMapper, projectMapper, userMapper, attachmentMapper,
                permissionService);
    }

    private User normalUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setRole("user");
        user.setNickname("张三");
        return user;
    }

    /** 含全部内置属性（status/priority/dueDate）与一个 member 负责人属性的项目 */
    private ProjectVO builtinProject() {
        ProjectVO project = new ProjectVO();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        project.setCardProperties("""
                [{"id":"status","type":"select"},
                 {"id":"priority","type":"select"},
                 {"id":"dueDate","type":"date"},
                 {"id":"assignee","type":"member"}]
                """);
        return project;
    }

    /** 未添加任何内置属性的项目（新建项目默认如此） */
    private ProjectVO plainProject() {
        ProjectVO project = new ProjectVO();
        project.setId(PLAIN_PROJECT_ID);
        project.setName("空白项目");
        project.setCardProperties("[]");
        return project;
    }

    private Block card(long id, Long projectId, Long createdBy, Map<String, Object> properties) {
        Block card = new Block();
        card.setId(id);
        card.setProjectId(projectId);
        card.setType(BlockTypes.CARD);
        card.setTitle("任务" + id);
        card.setCreatedBy(createdBy);
        card.setUpdateAt(LocalDateTime.now());
        Map<String, Object> fields = new HashMap<>();
        fields.put("properties", new HashMap<>(properties));
        card.setFields(fields);
        return card;
    }

    private void mockVisibleProjects(List<ProjectVO> projects, List<Block> cards) {
        when(userMapper.selectById(USER_ID)).thenReturn(normalUser());
        when(projectMapper.selectMyProjects(USER_ID)).thenReturn(projects);
        if (!projects.isEmpty()) {
            when(blockMapper.selectList(any())).thenReturn(cards);
            if (!cards.isEmpty()) {
                User creator = new User();
                creator.setId(OTHER_USER_ID);
                creator.setNickname("李四");
                when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(creator));
            }
        }
    }

    @Test
    void doneTaskIsNotOverdueAndDueDateAcceptsTimeSuffix() {
        // 已完成的逾期任务不计入逾期，计入已完成；dueDate 兼容带时间的存量格式
        Block done = card(1L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "status", "done",
                "dueDate", LocalDate.now().minusDays(3) + " 00:00:00"));
        mockVisibleProjects(List.of(builtinProject()), List.of(done));

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(1, vo.getTotalTasks());
        assertEquals(1, vo.getDoneTasks());
        assertEquals(0, vo.getOverdueTasks());
        assertTrue(vo.getOverdueList().isEmpty());
    }

    @Test
    void onlyP0CountsAsUrgent() {
        Block urgent = card(1L, PROJECT_ID, OTHER_USER_ID, Map.of("priority", "p0"));
        Block normal = card(2L, PROJECT_ID, OTHER_USER_ID, Map.of("priority", "p1"));
        Block plain = card(3L, PROJECT_ID, OTHER_USER_ID, Map.of());
        mockVisibleProjects(List.of(builtinProject()), List.of(urgent, normal, plain));

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(3, vo.getTotalTasks());
        assertEquals(1, vo.getUrgentTasks());
        assertEquals(1, vo.getUrgentList().size());
        assertEquals(1L, vo.getUrgentList().get(0).getId());
    }

    @Test
    void myTasksCountsOnlyAssignee() {
        // 仅统计当前用户为负责人属性值的未完成任务；纯创建者任务不计入
        Block createdByMe = card(1L, PROJECT_ID, USER_ID, Map.of());
        Block bothMine = card(2L, PROJECT_ID, USER_ID, Map.of("assignee", USER_ID.intValue()));
        Block assignedToMe = card(3L, PROJECT_ID, OTHER_USER_ID, Map.of("assignee", USER_ID.intValue()));
        Block others = card(4L, PROJECT_ID, OTHER_USER_ID, Map.of("assignee", OTHER_USER_ID.intValue()));
        mockVisibleProjects(List.of(builtinProject()), List.of(createdByMe, bothMine, assignedToMe, others));

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(4, vo.getTotalTasks());
        assertEquals(2, vo.getMyTasks());
        assertEquals(2, vo.getMyTaskList().size());
    }

    @Test
    void myTasksExcludesDoneTasks() {
        // 我负责但已完成的任务不计入"我负责的"，仍计入已完成
        Block doneMine = card(1L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "assignee", USER_ID.intValue(),
                "status", "done"));
        Block openMine = card(2L, PROJECT_ID, OTHER_USER_ID, Map.of("assignee", USER_ID.intValue()));
        mockVisibleProjects(List.of(builtinProject()), List.of(doneMine, openMine));

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(2, vo.getTotalTasks());
        assertEquals(1, vo.getDoneTasks());
        assertEquals(1, vo.getMyTasks());
        assertEquals(1, vo.getMyTaskList().size());
        assertEquals(2L, vo.getMyTaskList().get(0).getId());
    }

    @Test
    void projectWithoutBuiltinPropertiesDoesNotAffectDimensions() {
        // 空白项目的任务即使携带同名字段也不参与紧急/逾期/状态维度，也不计入"我负责的"（无 member 属性定义）
        Block fakeProps = card(1L, PLAIN_PROJECT_ID, USER_ID, Map.of(
                "priority", "p0",
                "status", "done",
                "dueDate", LocalDate.now().minusDays(1).toString()));
        mockVisibleProjects(List.of(plainProject()), List.of(fakeProps));

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(1, vo.getTotalTasks());
        assertEquals(0, vo.getMyTasks());
        assertEquals(0, vo.getUrgentTasks());
        assertEquals(0, vo.getOverdueTasks());
        assertEquals(0, vo.getDoneTasks());
    }

    @Test
    void overdueAndDueSoonAreSortedByDueDateAsc() {
        Block farOverdue = card(1L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "dueDate", LocalDate.now().minusDays(10).toString()));
        Block nearOverdue = card(2L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "dueDate", LocalDate.now().minusDays(1).toString()));
        Block dueToday = card(3L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "dueDate", LocalDate.now().toString()));
        Block dueInWeek = card(4L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "dueDate", LocalDate.now().plusDays(7).toString()));
        Block beyondWindow = card(5L, PROJECT_ID, OTHER_USER_ID, Map.of(
                "dueDate", LocalDate.now().plusDays(8).toString()));
        mockVisibleProjects(List.of(builtinProject()),
                List.of(nearOverdue, dueInWeek, farOverdue, beyondWindow, dueToday));

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(2, vo.getOverdueTasks());
        assertEquals(List.of(1L, 2L), vo.getOverdueList().stream().map(i -> i.getId()).toList());
        assertEquals(2, vo.getDueSoonTasks());
        assertEquals(List.of(3L, 4L), vo.getDueSoonList().stream().map(i -> i.getId()).toList());
    }

    @Test
    void emptyScopeReturnsZeroWithoutCardQuery() {
        when(userMapper.selectById(USER_ID)).thenReturn(normalUser());
        when(projectMapper.selectMyProjects(USER_ID)).thenReturn(List.of());

        DashboardVO vo = taskService.getDashboard(USER_ID);
        assertEquals(0, vo.getTotalTasks());
        assertEquals(0, vo.getMyTasks());
        assertTrue(vo.getMyTaskList().isEmpty());
        verifyNoInteractions(blockMapper);
    }
}
