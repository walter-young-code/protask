package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.DependencyPatchRequest;
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
import net.sf.mpxj.ConstraintType;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.Relation;
import net.sf.mpxj.RelationType;
import net.sf.mpxj.Task;
import net.sf.mpxj.TaskMode;
import net.sf.mpxj.TimeUnit;
import net.sf.mpxj.mspdi.MSPDIReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GanttProjectService 单元测试：WBS 门禁、树序编号、父子防环、四类型依赖环检测与 MSPDI 导出。
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

    private TaskDependency dep(Long predecessorId, Long successorId, String type, int lag) {
        TaskDependency dep = dep(predecessorId, successorId);
        dep.setType(type);
        dep.setLag(lag);
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

    // ---------- 四类型依赖 ----------

    @Test
    void createDependencyRejectsSelf() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 1L, "FS")));
        assertEquals("dependency.self", ex.getMessageKey());
    }

    @Test
    void createDependencyRejectsInvalidType() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, request(1L, 2L, "XX")));
        assertEquals("dependency.typeInvalid", ex.getMessageKey());
    }

    @Test
    void createDependencyRejectsLagOutOfRange() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        DependencyRequest tooBig = request(1L, 2L, "FS");
        tooBig.setLag(366);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, tooBig));
        assertEquals("dependency.lagInvalid", ex.getMessageKey());
        DependencyRequest tooSmall = request(1L, 2L, "FS");
        tooSmall.setLag(-366);
        ex = assertThrows(BusinessException.class,
                () -> service.createDependency(USER_ID, PROJECT_ID, tooSmall));
        assertEquals("dependency.lagInvalid", ex.getMessageKey());
    }

    @Test
    void createDependencyAcceptsAllFourTypes() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        when(blockMapper.selectList(any())).thenReturn(List.of(card(1L, null, 1), card(2L, null, 2)));
        when(dependencyMapper.selectCount(any())).thenReturn(0L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of());
        for (String type : new String[]{"FS", "SS", "FF", "SF"}) {
            DependencyRequest req = request(1L, 2L, type);
            req.setLag(-3);
            DependencyVO vo = service.createDependency(USER_ID, PROJECT_ID, req);
            assertEquals(type, vo.getType());
            assertEquals(-3, vo.getLag());
        }
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

    // ---------- 依赖修改 ----------

    @Test
    void updateDependencyChangesTypeAndLag() {
        TaskDependency existing = dep(1L, 2L);
        existing.setId(9L);
        when(dependencyMapper.selectById(9L)).thenReturn(existing);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        DependencyPatchRequest patch = new DependencyPatchRequest();
        patch.setType("SS");
        patch.setLag(-2);

        DependencyVO vo = service.updateDependency(USER_ID, 9L, patch);

        assertEquals("SS", vo.getType());
        assertEquals(-2, vo.getLag());
        verify(dependencyMapper).updateById(existing);
    }

    @Test
    void updateDependencyKeepsUnsetFields() {
        TaskDependency existing = dep(1L, 2L, "FF", 5);
        existing.setId(9L);
        when(dependencyMapper.selectById(9L)).thenReturn(existing);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));

        DependencyVO vo = service.updateDependency(USER_ID, 9L, new DependencyPatchRequest());

        assertEquals("FF", vo.getType());
        assertEquals(5, vo.getLag());
    }

    @Test
    void updateDependencyRejectsInvalidTypeAndLag() {
        TaskDependency existing = dep(1L, 2L);
        existing.setId(9L);
        when(dependencyMapper.selectById(9L)).thenReturn(existing);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        DependencyPatchRequest badType = new DependencyPatchRequest();
        badType.setType("XX");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateDependency(USER_ID, 9L, badType));
        assertEquals("dependency.typeInvalid", ex.getMessageKey());
        DependencyPatchRequest badLag = new DependencyPatchRequest();
        badLag.setLag(400);
        ex = assertThrows(BusinessException.class,
                () -> service.updateDependency(USER_ID, 9L, badLag));
        assertEquals("dependency.lagInvalid", ex.getMessageKey());
    }

    @Test
    void updateDependencyRejectsMissing() {
        when(dependencyMapper.selectById(9L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateDependency(USER_ID, 9L, new DependencyPatchRequest()));
        assertEquals("dependency.notFound", ex.getMessageKey());
    }

    @Test
    void updateDependencyRequiresWbs() {
        TaskDependency existing = dep(1L, 2L);
        existing.setId(9L);
        when(dependencyMapper.selectById(9L)).thenReturn(existing);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PLAIN_TEMPLATE));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateDependency(USER_ID, 9L, new DependencyPatchRequest()));
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

    @Test
    void exportMspdiRoundTripMapsFourRelationTypesAndWorkdaySemantics() throws Exception {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));

        // 摘要任务无自身日期，按子孙 min/max 汇总；里程碑 0 工期
        Block summary = card(1L, null, 1);
        Block child = card(2L, 1L, 1);
        putProp(child, "startDate", "2026-11-10");
        putProp(child, "dueDate", "2026-11-12");
        Block milestone = card(3L, 1L, 2);
        putProp(milestone, "startDate", "2026-11-15");
        putProp(milestone, "dueDate", "2026-11-15");
        milestone.setMilestone(true);
        Block leaf = card(4L, null, 2);
        putProp(leaf, "startDate", "2026-11-20");
        putProp(leaf, "dueDate", "2026-11-22");
        when(blockMapper.selectList(any())).thenReturn(List.of(summary, child, milestone, leaf));
        when(dependencyMapper.selectList(any())).thenReturn(List.of(
                dep(2L, 3L, "SS", 2),
                dep(3L, 4L, "FF", -1),
                dep(2L, 4L, "SF", 0),
                dep(1L, 4L, "FS", 0)));

        byte[] xml = service.exportMspdi(USER_ID, PROJECT_ID);
        ProjectFile file = new MSPDIReader().read(new ByteArrayInputStream(xml));

        Task childTask = taskByName(file, "任务2");
        Task milestoneTask = taskByName(file, "任务3");
        Task leafTask = taskByName(file, "任务4");
        Task summaryTask = taskByName(file, "任务1");

        // 工作日语义：start 08:00 / finish 17:00，工期含首日
        assertEquals(LocalDateTime.of(2026, 11, 10, 8, 0), childTask.getStart());
        assertEquals(LocalDateTime.of(2026, 11, 12, 17, 0), childTask.getFinish());
        assertEquals(3.0, days(file, childTask.getDuration()));
        // 里程碑：0 工期、start=finish
        assertEquals(Boolean.TRUE, milestoneTask.getMilestone());
        assertEquals(0.0, days(file, milestoneTask.getDuration()));
        assertEquals(milestoneTask.getStart(), milestoneTask.getFinish());
        // 摘要汇总：子孙 min(11-10)/max(11-15)
        assertEquals(LocalDateTime.of(2026, 11, 10, 8, 0), summaryTask.getStart());
        assertEquals(LocalDateTime.of(2026, 11, 15, 17, 0), summaryTask.getFinish());

        // 四类型链接映射与 lag（含负数提前量）
        Relation ss = predecessorOf(milestoneTask, "任务2");
        assertEquals(RelationType.START_START, ss.getType());
        assertEquals(2.0, days(file, ss.getLag()));
        Relation ff = predecessorOf(leafTask, "任务3");
        assertEquals(RelationType.FINISH_FINISH, ff.getType());
        assertEquals(-1.0, days(file, ff.getLag()));
        Relation sf = predecessorOf(leafTask, "任务2");
        assertEquals(RelationType.START_FINISH, sf.getType());
        Relation fs = predecessorOf(leafTask, "任务1");
        assertEquals(RelationType.FINISH_START, fs.getType());
    }

    // ---------- 自动排程引擎 ----------

    private void stubWbs(List<Block> cards, List<TaskDependency> deps) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(WBS_TEMPLATE));
        when(blockMapper.selectList(any())).thenReturn(cards);
        when(dependencyMapper.selectList(any())).thenReturn(deps);
    }

    private String prop(Block card, String key) {
        return String.valueOf(((Map<?, ?>) card.getFields().get("properties")).get(key));
    }

    @Test
    void rescheduleFsChainPropagatesAndRespectsExplicitDuration() {
        Block a = card(1L, null, 1);
        putProp(a, "startDate", "2026-11-01");
        putProp(a, "dueDate", "2026-11-03");
        Block b = card(2L, null, 2);
        b.setScheduleMode("auto");
        b.setDurationDays(3);
        putProp(b, "startDate", "2026-11-01");
        putProp(b, "dueDate", "2026-11-02");
        Block c = card(3L, null, 3);
        c.setScheduleMode("auto");
        putProp(c, "startDate", "2026-11-01");
        putProp(c, "dueDate", "2026-11-01");
        stubWbs(List.of(a, b, c), List.of(dep(1L, 2L), dep(2L, 3L)));

        service.rescheduleAutoTasks(PROJECT_ID);

        // 手动任务不动；B 显式工期 3 天；C 按自身日期推导工期 1 天
        assertEquals("2026-11-01", prop(a, "startDate"));
        assertEquals("2026-11-03", prop(a, "dueDate"));
        assertEquals("2026-11-04", prop(b, "startDate"));
        assertEquals("2026-11-06", prop(b, "dueDate"));
        assertEquals("2026-11-07", prop(c, "startDate"));
        assertEquals("2026-11-07", prop(c, "dueDate"));
        verify(blockMapper, times(2)).updateById(any(Block.class));
    }

    @Test
    void rescheduleSsFfSfFormulasWithNegativeLag() {
        Block p = card(1L, null, 1);
        putProp(p, "startDate", "2026-11-10");
        putProp(p, "dueDate", "2026-11-12");
        Block ss = card(2L, null, 2);
        ss.setScheduleMode("auto");
        putProp(ss, "startDate", "2026-11-10");
        putProp(ss, "dueDate", "2026-11-11");
        Block ff = card(3L, null, 3);
        ff.setScheduleMode("auto");
        putProp(ff, "startDate", "2026-11-10");
        putProp(ff, "dueDate", "2026-11-12");
        Block sf = card(4L, null, 4);
        sf.setScheduleMode("auto");
        putProp(sf, "startDate", "2026-11-10");
        putProp(sf, "dueDate", "2026-11-11");
        stubWbs(List.of(p, ss, ff, sf), List.of(
                dep(1L, 2L, "SS", -1), dep(1L, 3L, "FF", -1), dep(1L, 4L, "SF", 0)));

        service.rescheduleAutoTasks(PROJECT_ID);

        // SS: predStart+lag；FF: predEnd+lag-(工期-1)；SF: predStart+lag-(工期-1)
        assertEquals("2026-11-09", prop(ss, "startDate"));
        assertEquals("2026-11-10", prop(ss, "dueDate"));
        assertEquals("2026-11-09", prop(ff, "startDate"));
        assertEquals("2026-11-11", prop(ff, "dueDate"));
        assertEquals("2026-11-09", prop(sf, "startDate"));
        assertEquals("2026-11-10", prop(sf, "dueDate"));
    }

    @Test
    void rescheduleMultiplePredecessorsTakeMax() {
        Block p1 = card(1L, null, 1);
        putProp(p1, "startDate", "2026-11-01");
        putProp(p1, "dueDate", "2026-11-05");
        Block p2 = card(2L, null, 2);
        putProp(p2, "startDate", "2026-11-01");
        putProp(p2, "dueDate", "2026-11-10");
        Block s = card(3L, null, 3);
        s.setScheduleMode("auto");
        putProp(s, "startDate", "2026-11-01");
        putProp(s, "dueDate", "2026-11-02");
        stubWbs(List.of(p1, p2, s), List.of(dep(1L, 3L), dep(2L, 3L)));

        service.rescheduleAutoTasks(PROJECT_ID);

        assertEquals("2026-11-11", prop(s, "startDate"));
        assertEquals("2026-11-12", prop(s, "dueDate"));
    }

    @Test
    void rescheduleConstraintOverridesPredecessorAndClearReverts() {
        Block a = card(1L, null, 1);
        putProp(a, "startDate", "2026-11-01");
        putProp(a, "dueDate", "2026-11-03");
        Block b = card(2L, null, 2);
        b.setScheduleMode("auto");
        b.setDurationDays(2);
        b.setStartConstraint(LocalDate.of(2026, 11, 10));
        putProp(b, "startDate", "2026-11-01");
        putProp(b, "dueDate", "2026-11-02");
        stubWbs(List.of(a, b), List.of(dep(1L, 2L)));

        service.rescheduleAutoTasks(PROJECT_ID);
        // 约束晚于前置推导（11-04）：吸附到约束
        assertEquals("2026-11-10", prop(b, "startDate"));
        assertEquals("2026-11-11", prop(b, "dueDate"));

        b.setStartConstraint(null);
        service.rescheduleAutoTasks(PROJECT_ID);
        // 清除约束后回弹到前置驱动
        assertEquals("2026-11-04", prop(b, "startDate"));
        assertEquals("2026-11-05", prop(b, "dueDate"));
    }

    @Test
    void rescheduleNeverRewritesManualTasks() {
        Block a = card(1L, null, 1);
        putProp(a, "startDate", "2026-11-01");
        putProp(a, "dueDate", "2026-11-03");
        Block b = card(2L, null, 2);
        putProp(b, "startDate", "2026-11-01");
        putProp(b, "dueDate", "2026-11-02");
        stubWbs(List.of(a, b), List.of(dep(1L, 2L)));

        service.rescheduleAutoTasks(PROJECT_ID);

        assertEquals("2026-11-01", prop(b, "startDate"));
        verify(blockMapper, never()).updateById(any(Block.class));
    }

    @Test
    void rescheduleAutoMilestoneKeepsZeroDuration() {
        Block a = card(1L, null, 1);
        putProp(a, "startDate", "2026-11-01");
        putProp(a, "dueDate", "2026-11-03");
        Block m = card(2L, null, 2);
        m.setScheduleMode("auto");
        m.setMilestone(true);
        m.setDurationDays(5);
        putProp(m, "startDate", "2026-11-01");
        putProp(m, "dueDate", "2026-11-01");
        stubWbs(List.of(a, m), List.of(dep(1L, 2L)));

        service.rescheduleAutoTasks(PROJECT_ID);

        // 里程碑工期恒 0：显式工期被忽略，start=finish
        assertEquals("2026-11-04", prop(m, "startDate"));
        assertEquals("2026-11-04", prop(m, "dueDate"));
    }

    @Test
    void rescheduleSkipsSummaryAndRollsUpSummaryPredecessorDates() {
        Block a = card(1L, null, 1);
        putProp(a, "startDate", "2026-11-01");
        putProp(a, "dueDate", "2026-11-03");
        Block summary = card(2L, null, 2);
        summary.setScheduleMode("auto");
        putProp(summary, "startDate", "2026-11-01");
        putProp(summary, "dueDate", "2026-11-02");
        Block c1 = card(3L, 2L, 1);
        putProp(c1, "startDate", "2026-11-05");
        putProp(c1, "dueDate", "2026-11-06");
        Block c2 = card(4L, 2L, 2);
        putProp(c2, "startDate", "2026-11-08");
        putProp(c2, "dueDate", "2026-11-09");
        Block t = card(5L, null, 3);
        t.setScheduleMode("auto");
        putProp(t, "startDate", "2026-11-01");
        putProp(t, "dueDate", "2026-11-01");
        stubWbs(List.of(a, summary, c1, c2, t), List.of(dep(1L, 2L), dep(2L, 5L)));

        service.rescheduleAutoTasks(PROJECT_ID);

        // 摘要虽为 auto 且有前置，也不作为排程目标
        assertEquals("2026-11-01", prop(summary, "startDate"));
        assertEquals("2026-11-02", prop(summary, "dueDate"));
        // 摘要作为前置：取子孙汇总 max(11-09) 而非自身日期
        assertEquals("2026-11-10", prop(t, "startDate"));
        assertEquals("2026-11-10", prop(t, "dueDate"));
        verify(blockMapper, times(1)).updateById(any(Block.class));
    }

    @Test
    void rescheduleKeepsAnchorWithoutPredecessorsAndSchedulesUnscheduledFromPred() {
        Block a = card(1L, null, 1);
        putProp(a, "startDate", "2026-11-01");
        putProp(a, "dueDate", "2026-11-03");
        // 无前置无约束：当前日期为锚点（删依赖后同样保持，不主动归位）
        Block anchored = card(2L, null, 2);
        anchored.setScheduleMode("auto");
        putProp(anchored, "startDate", "2026-11-20");
        putProp(anchored, "dueDate", "2026-11-22");
        // 未排期且无前置：保持未排期
        Block unscheduled = card(3L, null, 3);
        unscheduled.setScheduleMode("auto");
        // 未排期但有前置：按前置推导，默认工期 1 天
        Block fromPred = card(4L, null, 4);
        fromPred.setScheduleMode("auto");
        stubWbs(List.of(a, anchored, unscheduled, fromPred), List.of(dep(1L, 4L)));

        service.rescheduleAutoTasks(PROJECT_ID);

        assertEquals("2026-11-20", prop(anchored, "startDate"));
        assertEquals("2026-11-22", prop(anchored, "dueDate"));
        assertEquals("null", prop(unscheduled, "startDate"));
        assertEquals("2026-11-04", prop(fromPred, "startDate"));
        assertEquals("2026-11-04", prop(fromPred, "dueDate"));
        verify(blockMapper, times(1)).updateById(any(Block.class));
    }

    @Test
    void rescheduleSkipsPlainProject() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project(PLAIN_TEMPLATE));
        service.rescheduleAutoTasks(PROJECT_ID);
        verify(blockMapper, never()).updateById(any(Block.class));
    }

    @Test
    void exportMspdiWritesTaskModeExplicitDurationAndStartConstraint() throws Exception {
        Block auto = card(1L, null, 1);
        auto.setScheduleMode("auto");
        auto.setDurationDays(5);
        auto.setStartConstraint(LocalDate.of(2026, 11, 10));
        putProp(auto, "startDate", "2026-11-10");
        putProp(auto, "dueDate", "2026-11-12");
        // 存量任务 scheduleMode=null → 导出为手动计划
        Block manual = card(2L, null, 2);
        putProp(manual, "startDate", "2026-11-20");
        putProp(manual, "dueDate", "2026-11-22");
        stubWbs(List.of(auto, manual), List.of());

        byte[] xml = service.exportMspdi(USER_ID, PROJECT_ID);
        String content = new String(xml, StandardCharsets.UTF_8);
        assertTrue(content.contains("<Manual>0</Manual>"));
        assertTrue(content.contains("<Manual>1</Manual>"));

        ProjectFile file = new MSPDIReader().read(new ByteArrayInputStream(xml));
        Task autoTask = taskByName(file, "任务1");
        Task manualTask = taskByName(file, "任务2");
        assertEquals(TaskMode.AUTO_SCHEDULED, autoTask.getTaskMode());
        assertEquals(TaskMode.MANUALLY_SCHEDULED, manualTask.getTaskMode());
        // 显式工期优先于日期 diff
        assertEquals(5.0, days(file, autoTask.getDuration()));
        assertEquals(3.0, days(file, manualTask.getDuration()));
        assertEquals(ConstraintType.START_NO_EARLIER_THAN, autoTask.getConstraintType());
        assertEquals(LocalDateTime.of(2026, 11, 10, 8, 0), autoTask.getConstraintDate());
    }

    private double days(ProjectFile file, net.sf.mpxj.Duration duration) {
        return duration.convertUnits(TimeUnit.DAYS, file.getProjectProperties()).getDuration();
    }

    private Task taskByName(ProjectFile file, String name) {
        return file.getTasks().stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst().orElseThrow();
    }

    private Relation predecessorOf(Task task, String predecessorName) {
        Relation relation = task.getPredecessors().stream()
                .filter(r -> predecessorName.equals(r.getPredecessorTask().getName()))
                .findFirst().orElseThrow();
        assertNotNull(relation.getLag());
        return relation;
    }
}