package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.TaskImportResultVO;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.ProjectMember;
import com.protask.entity.TaskDependency;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.TaskDependencyMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskExcelService 单元测试：按模板顺序导出（含自定义属性列）、WBS 工期/摘要卷算、依赖类型与延迟记号导出、
 * 内置字段映射导入（含显式工期）、WBS 前缀推导层级、依赖类型/延迟记号还原、全量校验失败整体不写入、非 WBS 项目忽略层级列。
 * 采用 LENIENT 桩策略，因服务内部多次复用 selectById/selectList。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskExcelServiceTest {

    private static final Long USER_ID = 2L;
    private static final Long PROJECT_ID = 100L;

    private static final String WBS_TEMPLATE =
            "[{\"id\":\"wbs\",\"name\":\"WBS编码\",\"type\":\"text\"},"
                    + "{\"id\":\"startDate\",\"name\":\"开始日期\",\"type\":\"date\"},"
                    + "{\"id\":\"dueDate\",\"name\":\"截止日期\",\"type\":\"date\"},"
                    + "{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\",\"options\":["
                    + "{\"id\":\"todo\",\"value\":\"待开始\"},{\"id\":\"in_progress\",\"value\":\"进行中\"},"
                    + "{\"id\":\"done\",\"value\":\"已完成\"}]},"
                    + "{\"id\":\"priority\",\"name\":\"优先级\",\"type\":\"select\",\"options\":["
                    + "{\"id\":\"p0\",\"value\":\"P0\"},{\"id\":\"p1\",\"value\":\"P1\"}]},"
                    + "{\"id\":\"assignee\",\"name\":\"负责人\",\"type\":\"member\"}]";

    private static final String PLAIN_TEMPLATE =
            "[{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\",\"options\":["
                    + "{\"id\":\"todo\",\"value\":\"待开始\"},{\"id\":\"in_progress\",\"value\":\"进行中\"}]},"
                    + "{\"id\":\"dueDate\",\"name\":\"截止日期\",\"type\":\"date\"}]";

    @Mock
    private BlockMapper blockMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private TaskDependencyMapper dependencyMapper;

    @Mock
    private PermissionService permissionService;

    @Mock
    private GanttProjectService ganttProjectService;

    private TaskExcelService service;

    /** 真实实例：effectiveDates 为纯树卷算逻辑，桩委托真实执行（依赖 mapper 传 null 即可） */
    private final GanttProjectService realGantt = new GanttProjectService(null, null, null, null, null);

    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.CHINA);
        service = new TaskExcelService(blockMapper, projectMapper, userMapper, projectMemberMapper,
                dependencyMapper, permissionService, ganttProjectService);
        idSeq.set(100);
        when(blockMapper.insert(any(Block.class))).thenAnswer(invocation -> {
            Block block = invocation.getArgument(0);
            block.setId(idSeq.incrementAndGet());
            return 1;
        });
        when(blockMapper.updateById(any(Block.class))).thenReturn(1);
        when(dependencyMapper.insert(any(TaskDependency.class))).thenReturn(1);
        when(ganttProjectService.liveCards(PROJECT_ID)).thenReturn(List.of());
        when(dependencyMapper.selectList(any())).thenReturn(List.of());
        when(ganttProjectService.effectiveDates(any()))
                .thenAnswer(inv -> realGantt.effectiveDates(inv.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    private Project project(String template) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("示例项目");
        project.setCardProperties(template);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project);
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
        Map<String, Object> fields = new HashMap<>();
        fields.put("properties", new HashMap<String, Object>());
        card.setFields(fields);
        return card;
    }

    @SuppressWarnings("unchecked")
    private void putProp(Block card, String key, Object value) {
        ((Map<String, Object>) card.getFields().get("properties")).put(key, value);
    }

    private void stubMembers() {
        ProjectMember member = new ProjectMember();
        member.setProjectId(PROJECT_ID);
        member.setUserId(5L);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(member));
        User user = new User();
        user.setId(5L);
        user.setUsername("zhangsan");
        user.setNickname("张三");
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(user));
    }

    /** 构造导入文件：headers + 数据行 */
    private MockMultipartFile workbook(String[] headers, String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tasks");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "tasks.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static final String[] WBS_HEADERS =
            {"WBS编码", "标题", "里程碑", "前置任务", "状态", "优先级", "负责人", "开始日期", "截止日期"};

    private static final String[] WBS_HEADERS_DURATION =
            {"WBS编码", "标题", "工期", "里程碑", "前置任务", "状态", "优先级", "负责人", "开始日期", "截止日期"};

    // ---------- 导出 ----------

    @Test
    void exportWbsProjectWritesTreeOrderAndAllColumns() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        Block root = card(11L, null, 1);
        Block childA = card(12L, 11L, 1);
        childA.setMilestone(true);
        putProp(childA, "status", "in_progress");
        putProp(childA, "priority", "p0");
        putProp(childA, "assignee", 5L);
        putProp(childA, "startDate", "2026-01-01");
        putProp(childA, "dueDate", "2026-01-05");
        Block childB = card(13L, 11L, 2);
        GanttProjectService.TaskTree tree = new GanttProjectService.TaskTree(
                List.of(root, childA, childB),
                Map.of(11L, "1", 12L, "1.1", 13L, "1.2"),
                Map.of(11L, 2, 12L, 0, 13L, 0),
                Map.of(11L, 0, 12L, 1, 13L, 1));
        when(ganttProjectService.buildTree(PROJECT_ID)).thenReturn(tree);
        TaskDependency dep = new TaskDependency();
        dep.setPredecessorId(12L);
        dep.setSuccessorId(13L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of(dep));
        stubMembers();

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            Row header = sheet.getRow(0);
            // 列序：固定前置列 + 模板属性顺序（wbs/startDate/dueDate/status/priority/assignee）
            assertEquals("WBS编码", header.getCell(0).getStringCellValue());
            assertEquals("标题", header.getCell(1).getStringCellValue());
            assertEquals("工期", header.getCell(2).getStringCellValue());
            assertEquals("里程碑", header.getCell(3).getStringCellValue());
            assertEquals("前置任务", header.getCell(4).getStringCellValue());
            assertEquals("开始日期", header.getCell(5).getStringCellValue());
            assertEquals("截止日期", header.getCell(6).getStringCellValue());
            assertEquals("状态", header.getCell(7).getStringCellValue());
            assertEquals("优先级", header.getCell(8).getStringCellValue());
            assertEquals("负责人", header.getCell(9).getStringCellValue());
            // 摘要行：工期/起止按子孙卷算（01-01 → 01-05 含首日共 5 天）
            Row rowRoot = sheet.getRow(1);
            assertEquals(CellType.NUMERIC, rowRoot.getCell(2).getCellType());
            assertEquals(5d, rowRoot.getCell(2).getNumericCellValue());
            assertEquals("2026-01-01", rowRoot.getCell(5).getStringCellValue());
            assertEquals("2026-01-05", rowRoot.getCell(6).getStringCellValue());
            Row rowA = sheet.getRow(2);
            assertEquals("1.1", rowA.getCell(0).getStringCellValue());
            // 里程碑 0 工期（数值单元格）
            assertEquals(CellType.NUMERIC, rowA.getCell(2).getCellType());
            assertEquals(0d, rowA.getCell(2).getNumericCellValue());
            assertEquals("是", rowA.getCell(3).getStringCellValue());
            assertEquals("2026-01-01", rowA.getCell(5).getStringCellValue());
            assertEquals("2026-01-05", rowA.getCell(6).getStringCellValue());
            assertEquals("进行中", rowA.getCell(7).getStringCellValue());
            assertEquals("P0", rowA.getCell(8).getStringCellValue());
            assertEquals("张三", rowA.getCell(9).getStringCellValue());
            Row rowB = sheet.getRow(3);
            assertEquals("1.1", rowB.getCell(4).getStringCellValue());
            // 未排期任务：工期/起止留空
            assertNull(rowB.getCell(2));
            assertNull(rowB.getCell(5));
            assertNull(rowB.getCell(6));
        }
        verify(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT);
    }

    @Test
    void exportPlainProjectHasNoWbsColumns() throws Exception {
        Project project = project(PLAIN_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        Block card = card(11L, null, 1);
        putProp(card, "status", "todo");
        when(ganttProjectService.liveCards(PROJECT_ID)).thenReturn(List.of(card));

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            // 列仅来自模板：标题 + 状态 + 截止日期（无 WBS/工期/里程碑/前置任务列）
            assertEquals("标题", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("状态", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("截止日期", sheet.getRow(0).getCell(2).getStringCellValue());
            assertNull(sheet.getRow(0).getCell(3));
            assertEquals("待开始", sheet.getRow(1).getCell(1).getStringCellValue());
            assertNull(sheet.getRow(1).getCell(2));
        }
    }

    @Test
    void exportColumnsFollowTemplateOrder() throws Exception {
        String template = "[{\"id\":\"qty\",\"name\":\"工程量\",\"type\":\"number\"},"
                + "{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\",\"options\":["
                + "{\"id\":\"todo\",\"value\":\"待开始\"}]},"
                + "{\"id\":\"note\",\"name\":\"备注\",\"type\":\"text\"}]";
        Project project = project(template);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        Block card = card(11L, null, 1);
        putProp(card, "qty", 12.5);
        putProp(card, "status", "todo");
        putProp(card, "note", "hello");
        when(ganttProjectService.liveCards(PROJECT_ID)).thenReturn(List.of(card));

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            assertEquals("标题", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("工程量", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("状态", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("备注", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("12.5", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("待开始", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("hello", sheet.getRow(1).getCell(3).getStringCellValue());
        }

        // 模板重排（status 置顶）后导出列序随之变化
        String reordered = "[{\"id\":\"status\",\"name\":\"状态\",\"type\":\"select\",\"options\":["
                + "{\"id\":\"todo\",\"value\":\"待开始\"}]},"
                + "{\"id\":\"qty\",\"name\":\"工程量\",\"type\":\"number\"},"
                + "{\"id\":\"note\",\"name\":\"备注\",\"type\":\"text\"}]";
        project(reordered);
        byte[] xlsx2 = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx2))) {
            Sheet sheet = wb.getSheet("Tasks");
            assertEquals("状态", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("工程量", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("备注", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("待开始", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("12.5", sheet.getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void exportCustomPropertyTypeValues() throws Exception {
        String template = "[{\"id\":\"phase\",\"name\":\"阶段\",\"type\":\"select\",\"options\":["
                + "{\"id\":\"a\",\"value\":\"阶段A\"},{\"id\":\"b\",\"value\":\"阶段B\"}]},"
                + "{\"id\":\"tags\",\"name\":\"标签\",\"type\":\"multiSelect\",\"options\":["
                + "{\"id\":\"x\",\"value\":\"标签X\"},{\"id\":\"y\",\"value\":\"标签Y\"}]},"
                + "{\"id\":\"done\",\"name\":\"完成\",\"type\":\"checkbox\"},"
                + "{\"id\":\"deliver\",\"name\":\"交付日\",\"type\":\"date\"},"
                + "{\"id\":\"owner\",\"name\":\"负责人\",\"type\":\"member\"}]";
        Project project = project(template);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        Block card = card(11L, null, 1);
        putProp(card, "phase", "a");
        putProp(card, "tags", List.of("x", "y"));
        putProp(card, "done", true);
        putProp(card, "deliver", "2026-05-01 08:00:00");
        putProp(card, "owner", 5L);
        when(ganttProjectService.liveCards(PROJECT_ID)).thenReturn(List.of(card));
        stubMembers();

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            Row row = sheet.getRow(1);
            assertEquals("阶段A", row.getCell(1).getStringCellValue());
            assertEquals("标签X, 标签Y", row.getCell(2).getStringCellValue());
            assertEquals("是", row.getCell(3).getStringCellValue());
            assertEquals("2026-05-01", row.getCell(4).getStringCellValue());
            assertEquals("张三", row.getCell(5).getStringCellValue());
        }
    }

    @Test
    void exportWbsDurationVariants() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        // 显式工期优先（起止仅 3 天但 durationDays=7）
        Block explicit = card(11L, null, 1);
        explicit.setDurationDays(7);
        putProp(explicit, "startDate", "2026-01-01");
        putProp(explicit, "dueDate", "2026-01-03");
        // 无显式工期：起止 diff+1
        Block derived = card(12L, null, 2);
        putProp(derived, "startDate", "2026-01-01");
        putProp(derived, "dueDate", "2026-01-05");
        // 里程碑 0 工期
        Block milestone = card(13L, null, 3);
        milestone.setMilestone(true);
        putProp(milestone, "startDate", "2026-01-02");
        putProp(milestone, "dueDate", "2026-01-02");
        // 未排期：留空
        Block unscheduled = card(14L, null, 4);
        GanttProjectService.TaskTree tree = new GanttProjectService.TaskTree(
                List.of(explicit, derived, milestone, unscheduled),
                Map.of(11L, "1", 12L, "2", 13L, "3", 14L, "4"),
                Map.of(11L, 0, 12L, 0, 13L, 0, 14L, 0),
                Map.of(11L, 0, 12L, 0, 13L, 0, 14L, 0));
        when(ganttProjectService.buildTree(PROJECT_ID)).thenReturn(tree);

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            assertEquals(7d, sheet.getRow(1).getCell(2).getNumericCellValue());
            assertEquals(5d, sheet.getRow(2).getCell(2).getNumericCellValue());
            assertEquals(0d, sheet.getRow(3).getCell(2).getNumericCellValue());
            assertNull(sheet.getRow(4).getCell(2));
        }
    }

    @Test
    void exportSummaryRollsUpNestedDates() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        Block root = card(11L, null, 1);
        Block mid = card(12L, 11L, 1);
        putProp(mid, "startDate", "2026-01-01");
        putProp(mid, "dueDate", "2026-01-05");
        Block leaf = card(13L, 12L, 1);
        putProp(leaf, "startDate", "2026-01-03");
        putProp(leaf, "dueDate", "2026-01-10");
        GanttProjectService.TaskTree tree = new GanttProjectService.TaskTree(
                List.of(root, mid, leaf),
                Map.of(11L, "1", 12L, "1.1", 13L, "1.1.1"),
                Map.of(11L, 1, 12L, 1, 13L, 0),
                Map.of(11L, 0, 12L, 1, 13L, 2));
        when(ganttProjectService.buildTree(PROJECT_ID)).thenReturn(tree);

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            // 根摘要：跨两层卷算 01-01 → 01-10（10 天）
            Row rowRoot = sheet.getRow(1);
            assertEquals(10d, rowRoot.getCell(2).getNumericCellValue());
            assertEquals("2026-01-01", rowRoot.getCell(5).getStringCellValue());
            assertEquals("2026-01-10", rowRoot.getCell(6).getStringCellValue());
            // 中间摘要：自身 01-05 被子孙 01-10 延展
            Row rowMid = sheet.getRow(2);
            assertEquals(10d, rowMid.getCell(2).getNumericCellValue());
            assertEquals("2026-01-10", rowMid.getCell(6).getStringCellValue());
            // 叶子：自身起止 diff+1
            Row rowLeaf = sheet.getRow(3);
            assertEquals(8d, rowLeaf.getCell(2).getNumericCellValue());
        }
    }

    @Test
    void exportWbsDependencyTypeAndLagTokens() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        Block root = card(11L, null, 1);
        Block childA = card(12L, 11L, 1);
        Block childB = card(13L, 11L, 2);
        GanttProjectService.TaskTree tree = new GanttProjectService.TaskTree(
                List.of(root, childA, childB),
                Map.of(11L, "1", 12L, "1.1", 13L, "1.2"),
                Map.of(11L, 2, 12L, 0, 13L, 0),
                Map.of(11L, 0, 12L, 1, 13L, 1));
        when(ganttProjectService.buildTree(PROJECT_ID)).thenReturn(tree);
        // 1.1SS+2 → 1.2；1.2FS-3 → 1.1；1.1 → 1（纯 FS 零延迟 → 裸编码）
        TaskDependency ss = new TaskDependency();
        ss.setPredecessorId(12L);
        ss.setSuccessorId(13L);
        ss.setType("SS");
        ss.setLag(2);
        TaskDependency fs = new TaskDependency();
        fs.setPredecessorId(13L);
        fs.setSuccessorId(12L);
        fs.setType("FS");
        fs.setLag(-3);
        TaskDependency plain = new TaskDependency();
        plain.setPredecessorId(12L);
        plain.setSuccessorId(11L);
        when(dependencyMapper.selectList(any())).thenReturn(List.of(ss, fs, plain));

        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            // 树序：root(1) / childA(1.1) / childB(1.2)；纯 FS 零延迟 → 裸编码
            assertEquals("1.1", sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("1.2FS-3", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("1.1SS+2", sheet.getRow(3).getCell(4).getStringCellValue());
        }
    }

    @Test
    void exportRequiresViewPermission() {
        project(WBS_TEMPLATE);
        doThrow(new BusinessException(com.protask.common.ResultCode.FORBIDDEN))
                .when(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.VIEW_PROJECT);
        assertThrows(BusinessException.class, () -> service.exportExcel(USER_ID, PROJECT_ID));
    }

    // ---------- 导入 ----------

    @Test
    void importCreatesCardsWithHierarchyAndDependencies() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        stubMembers();
        MockMultipartFile file = workbook(WBS_HEADERS, new String[][]{
                {"1", "父任务", "否", null, "待开始", null, null, null, null},
                {"1.1", "子任务A", "是", null, "进行中", "P0", "张三", "2026-02-01", "2026-02-10"},
                {"1.2", "子任务B", null, "1.1", null, null, null, null, null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(3, result.getCreated());
        assertEquals(1, result.getDependencyCount());
        ArgumentCaptor<Block> updates = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper, org.mockito.Mockito.times(3)).updateById(updates.capture());
        List<Block> updated = updates.getAllValues();
        // 第一遍插入 id 101/102/103；第二遍回写父子与排序
        assertNull(updated.get(0).getParentTaskId());
        assertEquals(1, updated.get(0).getSortOrder());
        assertEquals(101L, updated.get(1).getParentTaskId());
        assertEquals(1, updated.get(1).getSortOrder());
        assertEquals(101L, updated.get(2).getParentTaskId());
        assertEquals(2, updated.get(2).getSortOrder());
        assertTrue(updated.get(1).getMilestone());
        assertFalse(updated.get(0).getMilestone());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) updated.get(1).getFields().get("properties");
        assertEquals("in_progress", props.get("status"));
        assertEquals("p0", props.get("priority"));
        assertEquals(5L, props.get("assignee"));
        assertEquals("2026-02-01", props.get("startDate"));
        assertEquals("2026-02-10", props.get("dueDate"));
        ArgumentCaptor<TaskDependency> deps = ArgumentCaptor.forClass(TaskDependency.class);
        verify(dependencyMapper).insert(deps.capture());
        assertEquals(102L, deps.getValue().getPredecessorId());
        assertEquals(103L, deps.getValue().getSuccessorId());
        assertEquals("FS", deps.getValue().getType());
        verify(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS);
    }

    @Test
    void importStoresExplicitDurationForLeafTasksOnly() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        MockMultipartFile file = workbook(WBS_HEADERS_DURATION, new String[][]{
                {"1", "摘要", "5", "否", null, null, null, null, null, null},
                {"1.1", "叶子A", "3", "否", null, null, null, null, null, null},
                {"1.2", "叶子B", "2", "是", null, null, null, null, null, null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(3, result.getCreated());
        ArgumentCaptor<Block> updates = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper, org.mockito.Mockito.times(3)).updateById(updates.capture());
        List<Block> updated = updates.getAllValues();
        // 摘要行不写显式工期（按子孙卷算）；叶子普通任务写入；里程碑恒 0 不写
        assertNull(updated.get(0).getDurationDays());
        assertEquals(3, updated.get(1).getDurationDays());
        assertNull(updated.get(2).getDurationDays());
    }

    @Test
    void importPreservesDependencyTypeAndLag() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        MockMultipartFile file = workbook(WBS_HEADERS_DURATION, new String[][]{
                {"1", "任务A", null, null, null, null, null, null, null, null},
                {"2", "任务B", null, null, "1SS+2", null, null, null, null, null},
                {"3", "任务C", null, null, "1", null, null, null, null, null},
                {"4", "任务D", null, null, "2FF-3;3+5", null, null, null, null, null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(4, result.getCreated());
        assertEquals(4, result.getDependencyCount());
        ArgumentCaptor<TaskDependency> deps = ArgumentCaptor.forClass(TaskDependency.class);
        verify(dependencyMapper, org.mockito.Mockito.times(4)).insert(deps.capture());
        List<TaskDependency> inserted = deps.getAllValues();
        assertEquals(101L, inserted.get(0).getPredecessorId());
        assertEquals(102L, inserted.get(0).getSuccessorId());
        assertEquals("SS", inserted.get(0).getType());
        assertEquals(2, inserted.get(0).getLag());
        // 裸编码 → FS/0
        assertEquals("FS", inserted.get(1).getType());
        assertEquals(0, inserted.get(1).getLag());
        // FF-3 与无类型 +5（缺省 FS）
        assertEquals("FF", inserted.get(2).getType());
        assertEquals(-3, inserted.get(2).getLag());
        assertEquals("FS", inserted.get(3).getType());
        assertEquals(5, inserted.get(3).getLag());
    }

    @Test
    void importRejectsInvalidDurationAndPredTokens() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        MockMultipartFile file = workbook(WBS_HEADERS_DURATION, new String[][]{
                {"1", "坏工期", "3天", null, null, null, null, null, null, null},
                {"2", "坏依赖", null, null, "1XX", null, null, null, null, null},
                {"3", "坏延迟", null, null, "1FS+999", null, null, null, null, null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertEquals(0, result.getCreated());
        assertEquals(3, result.getErrors().size());
        verify(blockMapper, never()).insert(any(Block.class));
        verify(dependencyMapper, never()).insert(any(TaskDependency.class));
    }

    @Test
    void importValidationErrorsBlockAllWrites() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        stubMembers();
        MockMultipartFile file = workbook(WBS_HEADERS, new String[][]{
                {"1", null, null, null, null, null, null, null, null},
                {"1.1", "未知状态", null, null, "不存在", null, null, null, null},
                {"1.1", "重复编码", null, null, null, null, null, null, null},
                {"2.1", "缺父编码", null, null, null, null, null, null, null},
                {"3", "非法日期", null, null, null, null, null, "2026/02/01", null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertEquals(0, result.getCreated());
        assertEquals(5, result.getErrors().size());
        verify(blockMapper, never()).insert(any(Block.class));
        verify(dependencyMapper, never()).insert(any(TaskDependency.class));
    }

    @Test
    void importDetectsDependencyCycle() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        MockMultipartFile file = workbook(WBS_HEADERS, new String[][]{
                {"1", "任务A", null, "2", null, null, null, null, null},
                {"2", "任务B", null, "1", null, null, null, null, null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertFalse(result.getErrors().isEmpty());
        assertEquals(2, result.getErrors().size());
        verify(blockMapper, never()).insert(any(Block.class));
    }

    @Test
    void importPlainProjectIgnoresWbsColumns() throws Exception {
        Project project = project(PLAIN_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        MockMultipartFile file = workbook(WBS_HEADERS, new String[][]{
                {"1", "任务A", "是", "2", "进行中", null, null, null, null},
                {"1.1", "任务B", null, null, null, null, null, null, "2026-03-01"},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(2, result.getCreated());
        assertEquals(0, result.getDependencyCount());
        ArgumentCaptor<Block> updates = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper, org.mockito.Mockito.times(2)).updateById(updates.capture());
        for (Block block : updates.getAllValues()) {
            assertNull(block.getParentTaskId());
            assertFalse(block.getMilestone());
        }
        verify(dependencyMapper, never()).insert(any(TaskDependency.class));
    }

    @Test
    void importIgnoresColumnsMissingFromTemplate() throws Exception {
        // 模板无 priority/assignee/startDate：对应列值被忽略且不报错
        Project project = project(PLAIN_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        MockMultipartFile file = workbook(WBS_HEADERS, new String[][]{
                {null, "任务A", null, null, "待开始", "P9", "陌生人", "2026-01-01", null},
        });

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(1, result.getCreated());
        ArgumentCaptor<Block> updates = ArgumentCaptor.forClass(Block.class);
        verify(blockMapper).updateById(updates.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) updates.getValue().getFields().get("properties");
        assertEquals("todo", props.get("status"));
        assertFalse(props.containsKey("priority"));
        assertFalse(props.containsKey("startDate"));
    }

    @Test
    void importRejectsEmptyFile() {
        project(WBS_TEMPLATE);
        MockMultipartFile empty = new MockMultipartFile("file", "tasks.xlsx", null, new byte[0]);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.importExcel(USER_ID, PROJECT_ID, empty));
        assertEquals("excel.emptyFile", ex.getMessageKey());
    }

    @Test
    void importRejectsOverRowLimit() throws Exception {
        Project project = project(PLAIN_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            rows.add(new String[]{null, "任务" + i, null, null, null, null, null, null, null});
        }
        MockMultipartFile file = workbook(WBS_HEADERS, rows.toArray(new String[0][]));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.importExcel(USER_ID, PROJECT_ID, file));
        assertEquals("excel.rowLimit", ex.getMessageKey());
        verify(blockMapper, never()).insert(any(Block.class));
    }

    @Test
    void importReportsIgnoredColumns() throws Exception {
        Project project = project(PLAIN_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        String[] headers = {"标题", "状态", "备注"};
        MockMultipartFile file = workbook(headers, new String[][]{{"任务A", "待开始", "随便写"}});

        TaskImportResultVO result = service.importExcel(USER_ID, PROJECT_ID, file);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(List.of("备注"), result.getIgnoredColumns());
    }

    @Test
    void importRequiresManagePermission() throws Exception {
        project(WBS_TEMPLATE);
        doThrow(new BusinessException(com.protask.common.ResultCode.FORBIDDEN))
                .when(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS);
        MockMultipartFile file = workbook(WBS_HEADERS, new String[][]{{"1", "任务A", null, null, null, null, null, null, null}});
        assertThrows(BusinessException.class, () -> service.importExcel(USER_ID, PROJECT_ID, file));
        verify(blockMapper, never()).insert(any(Block.class));
    }

    // ---------- 模板 ----------

    @Test
    void templateContainsHeadersAndSampleRow() throws Exception {
        Project project = project(WBS_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(true);
        byte[] xlsx = service.importTemplate(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheet("Tasks");
            assertEquals("WBS编码", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("工期", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("示例任务", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(1d, sheet.getRow(1).getCell(2).getNumericCellValue());
        }
        verify(permissionService).checkPermission(USER_ID, PROJECT_ID, ProjectPermission.MANAGE_PROJECT_CARDS);
    }

    @Test
    void englishLocaleExportsEnglishHeaders() throws Exception {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        Project project = project(PLAIN_TEMPLATE);
        when(ganttProjectService.hasWbs(project)).thenReturn(false);
        when(ganttProjectService.liveCards(PROJECT_ID)).thenReturn(List.of());
        byte[] xlsx = service.exportExcel(USER_ID, PROJECT_ID);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            assertEquals("Title", wb.getSheet("Tasks").getRow(0).getCell(0).getStringCellValue());
        }
    }
}