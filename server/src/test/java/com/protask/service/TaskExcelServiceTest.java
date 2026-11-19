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
 * TaskExcelService 单元测试：固定列导出、内置字段映射导入、WBS 前缀推导层级、
 * FS 依赖建立、全量校验失败整体不写入、非 WBS 项目忽略层级列。
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
            assertEquals("WBS编码", header.getCell(0).getStringCellValue());
            assertEquals("标题", header.getCell(1).getStringCellValue());
            assertEquals("里程碑", header.getCell(2).getStringCellValue());
            assertEquals("前置任务", header.getCell(3).getStringCellValue());
            assertEquals("状态", header.getCell(4).getStringCellValue());
            assertEquals("优先级", header.getCell(5).getStringCellValue());
            assertEquals("负责人", header.getCell(6).getStringCellValue());
            assertEquals("开始日期", header.getCell(7).getStringCellValue());
            assertEquals("截止日期", header.getCell(8).getStringCellValue());
            Row rowA = sheet.getRow(2);
            assertEquals("1.1", rowA.getCell(0).getStringCellValue());
            assertEquals("是", rowA.getCell(2).getStringCellValue());
            assertEquals("进行中", rowA.getCell(4).getStringCellValue());
            assertEquals("P0", rowA.getCell(5).getStringCellValue());
            assertEquals("张三", rowA.getCell(6).getStringCellValue());
            assertEquals("2026-01-01", rowA.getCell(7).getStringCellValue());
            assertEquals("2026-01-05", rowA.getCell(8).getStringCellValue());
            Row rowB = sheet.getRow(3);
            assertEquals("1.1", rowB.getCell(3).getStringCellValue());
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
            assertEquals("标题", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("待开始", sheet.getRow(1).getCell(1).getStringCellValue());
            // 模板未添加优先级/负责人/开始日期：列存在但值为空
            assertNull(sheet.getRow(1).getCell(2));
            assertNull(sheet.getRow(1).getCell(3));
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
            assertEquals("1", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("示例任务", sheet.getRow(1).getCell(1).getStringCellValue());
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