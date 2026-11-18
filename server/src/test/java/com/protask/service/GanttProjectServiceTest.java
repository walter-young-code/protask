package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.DependencyRequest;
import com.protask.dto.DependencyVO;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.TaskDependency;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.TaskDependencyMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GanttProjectService 单元测试：WBS 门禁、树序编号、父子防环、FS 依赖环检测与 MSPDI 导出。
 * 采用 LENIENT 桩策略，因服务内部多次复用 selectById/selectList。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GanttProjectServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long PROJECT_ID = 100L;

    private static final String WBS_TEMPLATE =
            "[{\"id\":\"wbs\",\"name\":\"WBS编码\",\"type\":\"text\"},"
                    + "{\"id\":\"startDate\",\"name\":\"开始日期\",\"type\":\"date\"},"
                    + "{\"id\":\"dueDate\",\"name\":\"截止日期\",\"type\":\"date\"},"
                    + "{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\"}]";

    private static final String PLAIN_TEMPLATE =
            "[{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\"},"
                    + "{\"id\":\"dueDate\",\"name\":\"截止日期\",\"type\":\"date\"}]";

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TaskDependencyMapper dependencyMapper;

    @Mock
    private PermissionService permissionService;

    private GanttProjectService service;

    @BeforeEach
    void setUp() {
        service = new GanttProjectService(blockMapper, projectMapper, userMapper, dependencyMapper, permissionService);
    }

    private Project project(String template) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        project.setCardProperties(template);
        return project;
    }

    private Block card(Long id, Long parentTaskId, int sortOrder) {
        Block card = new Block();
        card.setId(id);
        card.setProjectId(PROJECT_ID);
        card.setType(BlockTypes.CARD);
        card.setTitle("任务" + id);
        card.setParentTaskId(parentTaskId);
        card.setSortOrder(sortOrder);
        card.setCreatedBy(USER_ID);
        card.setModifiedBy(USER_ID);
        Map<String, Object> fields = new HashMap<>();
        fields.put("properties", new HashMap<String, Object>());
        card.setFields(fields);
        return card;
    }

    @SuppressWarnings("unchecked")
    private void putProp(Block card, String key, Object value) {
        ((Map<String, Object>) card.getFields().get("properties")).put(key, value);
    }

    private TaskDependency dep(Long predecessorId, Long successorId) {
        TaskDependency dep = new TaskDependency();
        dep.setProjectId(PROJECT_ID);
        dep.setPredecessorId(predecessorId);
        dep.setSuccessorId(successorId);
        dep.setType("FS");
        dep.setLag(0);
        return dep;
    }

    private DependencyRequest request(Long predecessorId, Long successorId, String type) {
        DependencyRequest request = new DependencyRequest();
        request.setPredecessorId(predecessorId);
        request.setSuccessorId(successorId);
        request.setType(type);
        return request;
    }

    // ---------- 门禁 ----------

    @Test
    void hasWbsDetectsBuiltInWbsProperty() {
        assertTrue(service.hasWbs(project(WBS_TEMPLATE)));
        assertFalse(service.hasWbs(project(PLAIN_TEMPLATE)));
        assertFalse(service.hasWbs((Project) null));
        assertFalse(service.hasWbs(project("not-json")));
    }

    @Test
    void requireWbsRejectsPlainProject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PLAIN_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.requireWbs(PROJECT_ID));
        assertEquals("gantt.wbsRequired", ex.getMessageKey());
    }

    @Test
    void listDependenciesReturnsEmptyForPlainProject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PLAIN_TEMPLATE));
        assertTrue(service.listDependencies(USER_ID, PROJECT_ID).isEmpty());
    }

    // ---------- 树序编号 ----------

    @Test
    void buildTreeOrdersSiblingsAndNumbersWbs() {
        List<Block> cards = List.of(
                card(1L, null, 1), card(2L, null, 2),
                card(3L, 1L, 1), card(4L, 1L, 2), card(5L, 3L, 1));
        when(blockMapper.selectList(any())).thenReturn(cards);

        GanttProjectService.TaskTree tree = service.buildTree(PROJECT_ID);

        assertEquals(List.of(1L, 3L, 5L, 4L, 2L), tree.ordered().stream().map(Block::getId).toList());
        assertEquals("1", tree.wbs().get(1L));
        assertEquals("1.1", tree.wbs().get(3L));
        assertEquals("1.1.1", tree.wbs().get(5L));
        assertEquals("1.2", tree.wbs().get(4L));
        assertEquals("2", tree.wbs().get(2L));
        assertEquals(2, tree.childCount().get(1L));
        assertEquals(1, tree.childCount().get(3L));
        assertEquals(0, tree.childCount().get(5L));
        assertEquals(0, tree.depth().get(1L));
        assertEquals(2, tree.depth().get(5L));
    }

    @Test
    void buildTreePromotesOrphanParentToRoot() {
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, 999L, 1)));
        GanttProjectService.TaskTree tree = service.buildTree(PROJECT_ID);
        assertEquals("1", tree.wbs().get(1L));
        assertEquals(0, tree.depth().get(1L));
    }

    @Test
    void descendantIdsCollectsAllDescendants() {
        List<Block> cards = List.of(card(1L, null, 1), card(3L, 1L, 1), card(4L, 1L, 2), card(5L, 3L, 1));
        when(blockMapper.selectList(any())).thenReturn(cards);
        assertEquals(Set.of(3L, 4L, 5L), service.descendantIds(PROJECT_ID, 1L));
        assertTrue(service.descendantIds(PROJECT_ID, 5L).isEmpty());
    }

    @Test
    void nextSortOrderIsMaxSiblingPlusOne() {
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1), card(2L, null, 3), card(3L, null, 2)));
        assertEquals(4, service.nextSortOrder(PROJECT_ID, null));
    }

    // ---------- 父子防环 ----------

    @Test
    void validateParentRejectsSelf() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateParent(PROJECT_ID, 1L, 1L));
        assertEquals("task.parentSelf", ex.getMessageKey());
    }

    @Test
    void validateParentRejectsInvalidParent() {
        when(blockMapper.selectById(9L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateParent(PROJECT_ID, 1L, 9L));
        assertEquals("task.parentInvalid", ex.getMessageKey());
    }

    @Test
    void validateParentRejectsCycle() {
        Block child = card(3L, 1L, 1);
        when(blockMapper.selectById(3L)).thenReturn(child);
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1), child));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateParent(PROJECT_ID, 1L, 3L));
        assertEquals("task.parentCycle", ex.getMessageKey());
    }

    // ---------- FS 依赖 ----------

    @Test
    void createDependencyRejectsSelf() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 1L, "FS")));
        assertEquals("dependency.self", ex.getMessageKey());
    }

    @Test
    void createDependencyRejectsNonFs() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, "SS")));
        assertEquals("dependency.typeUnsupported", ex.getMessageKey());
    }

    @Test
    void createDependencyRejectsDuplicate() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1), card(2L, null, 2)));
        when(dependencyMapper.selectCount(any())).thenReturn(1L);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, "FS")));
        assertEquals("dependency.duplicate", ex.getMessageKey());
    }

    @Test
    void createDependencyRejectsCycle() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1), card(2L, null, 2)));
        when(dependencyMapper.selectCount(any())).thenReturn(0L);
        // 已存在 2→1，再建 1→2 会成环
        when(dependencyMapper.selectList(any())).thenReturn(List.of(dep(2L, 1L)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, "FS")));
        assertEquals("dependency.cycle", ex.getMessageKey());
    }

    @Test
    void createDependencyRejectsMissingTask() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, "FS")));
        assertEquals("task.notFound", ex.getMessageKey());
    }

    @Test
    void createDependencySuccessInsertsFs() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1), card(2L, null, 2)));
        when(dependencyMapper.selectCount(any())).thenReturn(0L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of());

        DependencyVO vo = service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, null));

        assertEquals(1L, vo.getPredecessorId());
        assertEquals(2L, vo.getSuccessorId());
        assertEquals("FS", vo.getType());
        assertEquals(0, vo.getLag());
        verify(dependencyMapper).insert(any(TaskDependency.class));
    }

    @Test
    void createDependencyRequiresWbs() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PLAIN_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, "FS")));
        assertEquals("gantt.wbsRequired", ex.getMessageKey());
    }

    // ---------- MSPDI 导出 ----------

    @Test
    void exportMspdiRequiresWbs() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PLAIN_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.exportMspdi(USER_ID, PROJECT_ID));
        assertEquals("gantt.wbsRequired", ex.getMessageKey());
    }

    @Test
    void exportMspdiGeneratesXmlWithWbsAndResources() {
        String template = "[{\"id\":\"wbs\",\"name\":\"WBS编码\",\"type\":\"text\"},"
                + "{\"id\":\"startDate\",\"name\":\"开始日期\",\"type\":\"date\"},"
                + "{\"id\":\"dueDate\",\"name\":\"截止日期\",\"type\":\"date\"},"
                + "{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\"},"
                + "{\"id\":\"owner\",\"name\":\"负责人\",\"type\":\"member\"}]";
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(template));

        Block parent = card(1L, null, 1);
        putProp(parent, "startDate", "2026-11-10");
        putProp(parent, "dueDate", "2026-11-20");
        putProp(parent, "status", "in_progress");
        Block child = card(2L, 1L, 1);
        putProp(child, "startDate", "2026-11-10");
        putProp(child, "dueDate", "2026-11-12");
        putProp(child, "status", "done");
        putProp(child, "owner", USER_ID);
        child.setMilestone(true);
        // 未标记同日任务：导出 Milestone=0（不再按同日推导）
        Block sameDay = card(3L, null, 2);
        putProp(sameDay, "startDate", "2026-11-15");
        putProp(sameDay, "dueDate", "2026-11-15");
        when(blockMapper.selectList(any())).thenReturn(List.of(parent, child, sameDay));
        when(dependencyMapper.selectList(any())).thenReturn(List.of(dep(1L, 2L)));

        User user = new User();
        user.setId(USER_ID);
        user.setNickname("张三");
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user));

        byte[] xml = service.exportMspdi(USER_ID, PROJECT_ID);
        String content = new String(xml, StandardCharsets.UTF_8);

        assertTrue(content.contains("schemas.microsoft.com/project"));
        assertTrue(content.contains("<WBS>1</WBS>"));
        assertTrue(content.contains("<WBS>1.1</WBS>"));
        assertTrue(content.contains("<Summary>1</Summary>"));
        assertTrue(content.contains("<Milestone>1</Milestone>"));
        assertTrue(content.contains("<Milestone>0</Milestone>"));
        assertTrue(content.contains("张三"));
        assertTrue(content.contains("<Type>1</Type>"));
    }
}