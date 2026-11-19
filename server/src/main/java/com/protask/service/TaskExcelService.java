package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.MessageUtil;
import com.protask.common.ResultCode;
import com.protask.dto.TaskImportErrorVO;
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
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 任务 Excel(.xlsx) 导入导出：固定列结构（标题 + 内置字段，WBS 项目另含 WBS编码/里程碑/前置任务列）。
 * 导出需查看权限、不设 WBS 门禁；导入仅新建任务，先全量校验后单事务写入，
 * 层级由 WBS 编码前缀推导（1.1.2 → 父为 1.1），依赖仅 FS、lag=0。自定义属性不参与。
 */
@Service
@RequiredArgsConstructor
public class TaskExcelService {

    /** 固定列（按输出顺序） */
    private enum Col { WBS, TITLE, MILESTONE, PREDECESSORS, STATUS, PRIORITY, ASSIGNEE, START_DATE, DUE_DATE }

    private static final Map<Col, String> HEADERS_ZH = Map.of(
            Col.WBS, "WBS编码", Col.TITLE, "标题", Col.MILESTONE, "里程碑",
            Col.PREDECESSORS, "前置任务", Col.STATUS, "状态", Col.PRIORITY, "优先级",
            Col.ASSIGNEE, "负责人", Col.START_DATE, "开始日期", Col.DUE_DATE, "截止日期");

    private static final Map<Col, String> HEADERS_EN = Map.of(
            Col.WBS, "WBS", Col.TITLE, "Title", Col.MILESTONE, "Milestone",
            Col.PREDECESSORS, "Predecessors", Col.STATUS, "Status", Col.PRIORITY, "Priority",
            Col.ASSIGNEE, "Assignee", Col.START_DATE, "Start Date", Col.DUE_DATE, "Due Date");

    private static final int MAX_ROWS = 2000;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final Pattern WBS_PATTERN = Pattern.compile("\\d+(\\.\\d+)*");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BlockMapper blockMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TaskDependencyMapper dependencyMapper;
    private final PermissionService permissionService;
    private final GanttProjectService ganttProjectService;

    // ---------- 导出 ----------

    /** 导出项目任务为 .xlsx（所有项目可用；WBS 项目按树序输出并携带层级/依赖列） */
    public byte[] exportExcel(Long userId, Long projectId) {
        Project project = checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        boolean hasWbs = ganttProjectService.hasWbs(project);
        List<Block> cards;
        Map<Long, String> wbs;
        if (hasWbs) {
            GanttProjectService.TaskTree tree = ganttProjectService.buildTree(projectId);
            cards = tree.ordered();
            wbs = tree.wbs();
        } else {
            cards = new ArrayList<>(ganttProjectService.liveCards(projectId));
            cards.sort(Comparator.comparing(Block::getCreateAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Block::getId));
            wbs = Map.of();
        }
        List<JsonNode> template = parseTemplate(project);
        JsonNode statusProp = propById(template, "status");
        JsonNode priorityProp = propById(template, "priority");
        String memberPropId = firstMemberPropId(template);
        Map<Long, String> nicknames = assigneeNicknames(cards, memberPropId);
        // 依赖列：successor → 其 predecessor 的 WBS 编码列表
        Map<Long, List<String>> predsBySuccessor = new HashMap<>();
        if (hasWbs) {
            for (TaskDependency dep : dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                    .eq(TaskDependency::getProjectId, projectId))) {
                String predWbs = wbs.get(dep.getPredecessorId());
                if (predWbs != null) {
                    predsBySuccessor.computeIfAbsent(dep.getSuccessorId(), k -> new ArrayList<>()).add(predWbs);
                }
            }
        }
        boolean en = isEnglish();
        List<Col> cols = columns(hasWbs);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tasks");
            Row header = sheet.createRow(0);
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            for (int i = 0; i < cols.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(header(cols.get(i), en));
                cell.setCellStyle(bold);
            }
            int rowIdx = 1;
            for (Block card : cards) {
                Map<String, Object> props = properties(card);
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < cols.size(); i++) {
                    Col col = cols.get(i);
                    String value = switch (col) {
                        case WBS -> wbs.get(card.getId());
                        case TITLE -> card.getTitle();
                        case MILESTONE -> Boolean.TRUE.equals(card.getMilestone()) ? yesNo(true, en) : yesNo(false, en);
                        case PREDECESSORS -> String.join(";", predsBySuccessor.getOrDefault(card.getId(), List.of()));
                        case STATUS -> optionDisplay(statusProp, asString(props.get("status")));
                        case PRIORITY -> optionDisplay(priorityProp, asString(props.get("priority")));
                        case ASSIGNEE -> {
                            Object assignee = memberPropId == null ? null : props.get(memberPropId);
                            yield assignee == null ? null
                                    : nicknames.getOrDefault(Long.valueOf(assignee.toString()), null);
                        }
                        case START_DATE -> dateText(asString(props.get(GanttProjectService.PROP_START_DATE)));
                        case DUE_DATE -> dateText(asString(props.get(GanttProjectService.PROP_DUE_DATE)));
                    };
                    if (value != null && !value.isEmpty()) {
                        row.createCell(i).setCellValue(value);
                    }
                }
            }
            for (int i = 0; i < cols.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            return toBytes(wb);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "excel.exportFailed");
        }
    }

    // ---------- 导入模板 ----------

    /** 下载导入模板：固定表头 + 一行示例（WBS 列按项目是否启用输出） */
    public byte[] importTemplate(Long userId, Long projectId) {
        Project project = checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_CARDS);
        boolean hasWbs = ganttProjectService.hasWbs(project);
        boolean en = isEnglish();
        List<JsonNode> template = parseTemplate(project);
        List<Col> cols = columns(hasWbs);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Tasks");
            Row header = sheet.createRow(0);
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            for (int i = 0; i < cols.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(header(cols.get(i), en));
                cell.setCellStyle(bold);
            }
            Row sample = sheet.createRow(1);
            for (int i = 0; i < cols.size(); i++) {
                Col col = cols.get(i);
                String value = switch (col) {
                    case WBS -> "1";
                    case TITLE -> en ? "Example task" : "示例任务";
                    case MILESTONE -> yesNo(false, en);
                    case STATUS -> firstOptionDisplay(propById(template, "status"), en ? "To do" : "待开始");
                    case PRIORITY -> firstOptionDisplay(propById(template, "priority"), "P1");
                    default -> null;
                };
                if (value != null) {
                    sample.createCell(i).setCellValue(value);
                }
            }
            for (int i = 0; i < cols.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            return toBytes(wb);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "excel.exportFailed");
        }
    }

    // ---------- 导入 ----------

    /**
     * 导入 Excel 仅新建任务：先全量逐行校验（errors 非空则整体不写入），
     * 通过后按行序插入卡片，再按 WBS 前缀回写父子与排序，最后建立 FS 依赖。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskImportResultVO importExcel(Long userId, Long projectId, MultipartFile file) {
        Project project = checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_CARDS);
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "excel.emptyFile");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "excel.fileTooLarge");
        }
        boolean hasWbs = ganttProjectService.hasWbs(project);
        List<JsonNode> template = parseTemplate(project);
        JsonNode statusProp = propById(template, "status");
        JsonNode priorityProp = propById(template, "priority");
        String memberPropId = firstMemberPropId(template);
        Map<String, Long> memberIds = memberIdsByName(projectId, memberPropId != null);

        TaskImportResultVO result = new TaskImportResultVO();
        List<ParsedRow> rows;
        Map<Col, Integer> colIndex;
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheet("Tasks");
            if (sheet == null) {
                sheet = wb.getSheetAt(0);
            }
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "excel.emptyFile");
            }
            colIndex = new LinkedHashMap<>();
            for (Cell cell : headerRow) {
                String text = cellText(cell);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                Col col = matchColumn(text);
                if (col == null) {
                    result.getIgnoredColumns().add(text);
                } else {
                    colIndex.putIfAbsent(col, cell.getColumnIndex());
                }
            }
            if (!colIndex.containsKey(Col.TITLE)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "excel.titleColumnMissing");
            }
            rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                ParsedRow parsed = readRow(row, r + 1, colIndex, hasWbs);
                if (parsed == null) {
                    continue;
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "excel.rowLimit", MAX_ROWS);
                }
                rows.add(parsed);
            }
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "excel.parseFailed");
        }
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "excel.emptyFile");
        }
        validate(rows, hasWbs, statusProp, priorityProp, memberPropId, memberIds, result);
        if (!result.getErrors().isEmpty()) {
            return result;
        }
        int depCount = writeRows(userId, projectId, rows, hasWbs, statusProp, priorityProp, memberPropId, template);
        if (hasWbs) {
            // 导入写入日期/依赖后传播自动排程
            ganttProjectService.rescheduleAutoTasks(projectId);
        }
        result.setCreated(rows.size());
        result.setDependencyCount(depCount);
        return result;
    }

    /** 读取单行；整行为空返回 null */
    private ParsedRow readRow(Row row, int rowNum, Map<Col, Integer> colIndex, boolean hasWbs) {
        ParsedRow parsed = new ParsedRow();
        parsed.rowNum = rowNum;
        boolean any = false;
        for (Map.Entry<Col, Integer> entry : colIndex.entrySet()) {
            Col col = entry.getKey();
            if (!hasWbs && (col == Col.WBS || col == Col.MILESTONE || col == Col.PREDECESSORS)) {
                continue;
            }
            String text = cellText(row.getCell(entry.getValue()));
            if (text != null && !text.isEmpty()) {
                any = true;
            }
            switch (col) {
                case WBS -> parsed.wbs = text;
                case TITLE -> parsed.title = text;
                case MILESTONE -> parsed.milestoneText = text;
                case PREDECESSORS -> parsed.predecessorsText = text;
                case STATUS -> parsed.statusText = text;
                case PRIORITY -> parsed.priorityText = text;
                case ASSIGNEE -> parsed.assigneeText = text;
                case START_DATE -> parsed.startText = text;
                case DUE_DATE -> parsed.dueText = text;
            }
        }
        return any ? parsed : null;
    }

    /** 全量校验：逐行收集错误 + 文件内依赖图环检测 */
    private void validate(List<ParsedRow> rows, boolean hasWbs, JsonNode statusProp, JsonNode priorityProp,
                          String memberPropId, Map<String, Long> memberIds, TaskImportResultVO result) {
        boolean en = isEnglish();
        // 先收集文件内全部合法 WBS 编码，供父编码/前置引用校验
        Set<String> codes = new HashSet<>();
        Map<String, Integer> codeRows = new HashMap<>();
        if (hasWbs) {
            for (ParsedRow row : rows) {
                if (row.wbs == null || row.wbs.isEmpty()) {
                    continue;
                }
                if (!WBS_PATTERN.matcher(row.wbs).matches()) {
                    error(result, row.rowNum, "excel.wbsInvalid", row.wbs);
                    continue;
                }
                if (codeRows.putIfAbsent(row.wbs, row.rowNum) != null) {
                    error(result, row.rowNum, "excel.wbsDuplicate", row.wbs);
                    continue;
                }
                codes.add(row.wbs);
            }
        }
        // 文件内依赖边（节点键：WBS 编码，无编码行用 row:行号 占位）
        Map<String, List<String>> edges = new HashMap<>();
        for (ParsedRow row : rows) {
            if (row.title == null || row.title.isEmpty()) {
                error(result, row.rowNum, "excel.titleRequired");
            }
            if (hasWbs && row.wbs != null && !row.wbs.isEmpty() && codes.contains(row.wbs) && row.wbs.contains(".")) {
                String parentCode = row.wbs.substring(0, row.wbs.lastIndexOf('.'));
                if (!codes.contains(parentCode)) {
                    error(result, row.rowNum, "excel.wbsParentMissing", row.wbs, parentCode);
                }
            }
            if (statusProp != null && row.statusText != null && !row.statusText.isEmpty()) {
                row.statusOptionId = resolveOption(statusProp, row.statusText);
                if (row.statusOptionId == null) {
                    error(result, row.rowNum, "excel.unknownOption", header(Col.STATUS, en), row.statusText);
                }
            }
            if (priorityProp != null && row.priorityText != null && !row.priorityText.isEmpty()) {
                row.priorityOptionId = resolveOption(priorityProp, row.priorityText);
                if (row.priorityOptionId == null) {
                    error(result, row.rowNum, "excel.unknownOption", header(Col.PRIORITY, en), row.priorityText);
                }
            }
            if (memberPropId != null && row.assigneeText != null && !row.assigneeText.isEmpty()) {
                row.assigneeId = memberIds.get(row.assigneeText);
                if (row.assigneeId == null) {
                    error(result, row.rowNum, "excel.unknownMember", row.assigneeText);
                }
            }
            row.startDate = parseDateCell(row.startText, header(Col.START_DATE, en), row.rowNum, result);
            row.dueDate = parseDateCell(row.dueText, header(Col.DUE_DATE, en), row.rowNum, result);
            if (hasWbs && row.milestoneText != null && !row.milestoneText.isEmpty()) {
                Boolean milestone = parseYesNo(row.milestoneText);
                if (milestone == null) {
                    error(result, row.rowNum, "excel.badMilestone", row.milestoneText);
                } else {
                    row.milestone = milestone;
                }
            }
            if (hasWbs && row.predecessorsText != null && !row.predecessorsText.isEmpty()) {
                String nodeKey = row.wbs != null && !row.wbs.isEmpty() ? row.wbs : "row:" + row.rowNum;
                for (String code : row.predecessorsText.split("[;；]")) {
                    String pred = code.trim();
                    if (pred.isEmpty() || row.predecessors.contains(pred)) {
                        continue;
                    }
                    if (pred.equals(row.wbs)) {
                        error(result, row.rowNum, "excel.predSelf");
                        continue;
                    }
                    if (!codes.contains(pred)) {
                        error(result, row.rowNum, "excel.predNotFound", pred);
                        continue;
                    }
                    row.predecessors.add(pred);
                    edges.computeIfAbsent(pred, k -> new ArrayList<>()).add(nodeKey);
                }
            }
        }
        // 环检测（Kahn 拓扑排序）：文件任务均为新建，与存量依赖图不相交，仅需校验文件内部
        if (hasWbs && !edges.isEmpty() && hasCycle(edges, rows)) {
            for (ParsedRow row : rows) {
                if (!row.predecessors.isEmpty()) {
                    error(result, row.rowNum, "excel.depCycle");
                }
            }
        }
    }

    /** Kahn 拓扑排序判断文件内依赖图是否成环 */
    private boolean hasCycle(Map<String, List<String>> edges, List<ParsedRow> rows) {
        Set<String> nodes = new HashSet<>();
        Map<String, Integer> inDegree = new HashMap<>();
        for (ParsedRow row : rows) {
            String key = row.wbs != null && !row.wbs.isEmpty() ? row.wbs : "row:" + row.rowNum;
            nodes.add(key);
            inDegree.putIfAbsent(key, 0);
        }
        for (Map.Entry<String, List<String>> entry : edges.entrySet()) {
            for (String succ : entry.getValue()) {
                inDegree.merge(succ, 1, Integer::sum);
            }
        }
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.poll();
            visited++;
            for (String succ : edges.getOrDefault(node, List.of())) {
                if (inDegree.merge(succ, -1, Integer::sum) == 0) {
                    queue.add(succ);
                }
            }
        }
        return visited < nodes.size();
    }

    /** 校验通过后写入：两遍法（先插卡片，再回写父子/排序），最后建立依赖 */
    private int writeRows(Long userId, Long projectId, List<ParsedRow> rows, boolean hasWbs,
                          JsonNode statusProp, JsonNode priorityProp, String memberPropId, List<JsonNode> template) {
        boolean hasStart = propById(template, GanttProjectService.PROP_START_DATE) != null;
        boolean hasDue = propById(template, GanttProjectService.PROP_DUE_DATE) != null;
        // 存量同级最大 sortOrder（按父分组，null 父=根），新任务在其后递增
        Map<Long, Integer> maxSort = new HashMap<>();
        for (Block card : ganttProjectService.liveCards(projectId)) {
            maxSort.merge(card.getParentTaskId(), card.getSortOrder() == null ? 0 : card.getSortOrder(), Math::max);
        }
        Map<String, Long> idByWbs = new HashMap<>();
        List<Block> inserted = new ArrayList<>();
        for (ParsedRow row : rows) {
            Block card = new Block();
            card.setProjectId(projectId);
            card.setSchemaVer(1L);
            card.setType(BlockTypes.CARD);
            card.setTitle(row.title);
            card.setMilestone(hasWbs && row.milestone);
            card.setCreatedBy(userId);
            card.setModifiedBy(userId);
            Map<String, Object> props = new HashMap<>();
            if (statusProp != null && row.statusOptionId != null) {
                props.put("status", row.statusOptionId);
            }
            if (priorityProp != null && row.priorityOptionId != null) {
                props.put("priority", row.priorityOptionId);
            }
            if (memberPropId != null && row.assigneeId != null) {
                props.put(memberPropId, row.assigneeId);
            }
            if (hasStart && row.startDate != null) {
                props.put(GanttProjectService.PROP_START_DATE, row.startDate.toString());
            }
            if (hasDue && row.dueDate != null) {
                props.put(GanttProjectService.PROP_DUE_DATE, row.dueDate.toString());
            }
            Map<String, Object> fields = new HashMap<>();
            fields.put("properties", props);
            card.setFields(fields);
            blockMapper.insert(card);
            inserted.add(card);
            if (hasWbs && row.wbs != null && !row.wbs.isEmpty()) {
                idByWbs.put(row.wbs, card.getId());
            }
        }
        // 第二遍：按 WBS 前缀回写父任务与同级排序
        for (int i = 0; i < rows.size(); i++) {
            ParsedRow row = rows.get(i);
            Block card = inserted.get(i);
            Long parentId = null;
            if (hasWbs && row.wbs != null && row.wbs.contains(".")) {
                parentId = idByWbs.get(row.wbs.substring(0, row.wbs.lastIndexOf('.')));
            }
            card.setParentTaskId(parentId);
            card.setSortOrder(maxSort.merge(parentId, 1, Integer::sum));
            blockMapper.updateById(card);
        }
        // 依赖：前置 WBS → 新任务 id，FS/lag=0
        int depCount = 0;
        if (hasWbs) {
            for (int i = 0; i < rows.size(); i++) {
                ParsedRow row = rows.get(i);
                for (String pred : row.predecessors) {
                    Long predecessorId = idByWbs.get(pred);
                    if (predecessorId == null) {
                        continue;
                    }
                    TaskDependency dep = new TaskDependency();
                    dep.setProjectId(projectId);
                    dep.setPredecessorId(predecessorId);
                    dep.setSuccessorId(inserted.get(i).getId());
                    dep.setType("FS");
                    dep.setLag(0);
                    dep.setCreateAt(LocalDateTime.now());
                    dependencyMapper.insert(dep);
                    depCount++;
                }
            }
        }
        return depCount;
    }

    // ---------- 辅助 ----------

    /** 解析后的导入行（rowNum 为 Excel 行号，含表头行） */
    private static class ParsedRow {
        int rowNum;
        String wbs;
        String title;
        String milestoneText;
        String predecessorsText;
        String statusText;
        String priorityText;
        String assigneeText;
        String startText;
        String dueText;
        String statusOptionId;
        String priorityOptionId;
        Long assigneeId;
        LocalDate startDate;
        LocalDate dueDate;
        boolean milestone;
        List<String> predecessors = new ArrayList<>();
    }

    private void error(TaskImportResultVO result, int row, String messageKey, Object... args) {
        result.getErrors().add(new TaskImportErrorVO(row, MessageUtil.get(messageKey, args)));
    }

    private List<Col> columns(boolean hasWbs) {
        List<Col> cols = new ArrayList<>();
        if (hasWbs) {
            cols.add(Col.WBS);
        }
        cols.add(Col.TITLE);
        if (hasWbs) {
            cols.add(Col.MILESTONE);
            cols.add(Col.PREDECESSORS);
        }
        cols.add(Col.STATUS);
        cols.add(Col.PRIORITY);
        cols.add(Col.ASSIGNEE);
        cols.add(Col.START_DATE);
        cols.add(Col.DUE_DATE);
        return cols;
    }

    private String header(Col col, boolean en) {
        return (en ? HEADERS_EN : HEADERS_ZH).get(col);
    }

    /** 列头匹配：同时识别中/英文（英文忽略大小写） */
    private Col matchColumn(String text) {
        for (Col col : Col.values()) {
            if (HEADERS_ZH.get(col).equals(text) || HEADERS_EN.get(col).equalsIgnoreCase(text)) {
                return col;
            }
        }
        return null;
    }

    private boolean isEnglish() {
        return "en".equalsIgnoreCase(LocaleContextHolder.getLocale().getLanguage());
    }

    private String yesNo(boolean value, boolean en) {
        return en ? (value ? "Yes" : "No") : (value ? "是" : "否");
    }

    private Boolean parseYesNo(String text) {
        String normalized = text.trim().toLowerCase();
        if (List.of("是", "yes", "true", "1").contains(normalized)) {
            return true;
        }
        if (List.of("否", "no", "false", "0").contains(normalized)) {
            return false;
        }
        return null;
    }

    private LocalDate parseDateCell(String text, String columnHeader, int rowNum, TaskImportResultVO result) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (DateTimeParseException e) {
            error(result, rowNum, "excel.badDate", columnHeader, text);
            return null;
        }
    }

    /** 单元格文本：日期格式单元格转 yyyy-MM-dd，数值去尾零，公式取缓存值 */
    private String cellText(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        String text = switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : numericText(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null;
        };
        return text == null ? null : text.trim();
    }

    private String numericText(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private Project checkProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeleteAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.notFound");
        }
        return project;
    }

    /** 解析项目属性模板为 JsonNode 列表（非法 JSON 视为空模板） */
    private List<JsonNode> parseTemplate(Project project) {
        List<JsonNode> result = new ArrayList<>();
        if (project.getCardProperties() == null) {
            return result;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(project.getCardProperties());
            if (root.isArray()) {
                root.forEach(result::add);
            }
        } catch (JsonProcessingException ignored) {
            // 非法 JSON 视为空模板
        }
        return result;
    }

    private JsonNode propById(List<JsonNode> template, String id) {
        for (JsonNode node : template) {
            if (id.equals(node.path("id").asText(null))) {
                return node;
            }
        }
        return null;
    }

    private String firstMemberPropId(List<JsonNode> template) {
        for (JsonNode node : template) {
            if ("member".equals(node.path("type").asText(null))) {
                return node.path("id").asText(null);
            }
        }
        return null;
    }

    /** 选项 id → 显示值（孤儿 id 返回 null） */
    private String optionDisplay(JsonNode prop, String optionId) {
        if (prop == null || optionId == null || optionId.isEmpty()) {
            return null;
        }
        for (JsonNode option : prop.path("options")) {
            if (optionId.equals(option.path("id").asText(null))) {
                return option.path("value").asText(null);
            }
        }
        return null;
    }

    private String firstOptionDisplay(JsonNode prop, String fallback) {
        if (prop != null && prop.path("options").isArray() && !prop.path("options").isEmpty()) {
            return prop.path("options").get(0).path("value").asText(fallback);
        }
        return fallback;
    }

    /** 显示值或选项 id → 选项 id（显示值忽略大小写），无匹配返回 null */
    private String resolveOption(JsonNode prop, String text) {
        for (JsonNode option : prop.path("options")) {
            String id = option.path("id").asText(null);
            String value = option.path("value").asText(null);
            if (text.equals(id) || text.equalsIgnoreCase(value)) {
                return id;
            }
        }
        return null;
    }

    /** 项目成员 昵称/用户名 → userId（负责人匹配用） */
    private Map<String, Long> memberIdsByName(Long projectId, boolean needed) {
        if (!needed) {
            return Map.of();
        }
        List<ProjectMember> members = projectMemberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId));
        if (members.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = new LinkedHashSet<>();
        members.forEach(m -> userIds.add(m.getUserId()));
        Map<String, Long> byName = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            if (user.getNickname() != null && !user.getNickname().isEmpty()) {
                byName.putIfAbsent(user.getNickname(), user.getId());
            }
            if (user.getUsername() != null) {
                byName.putIfAbsent(user.getUsername(), user.getId());
            }
        }
        return byName;
    }

    /** 批量查询负责人昵称（避免 N+1） */
    private Map<Long, String> assigneeNicknames(List<Block> cards, String memberPropId) {
        if (memberPropId == null) {
            return Map.of();
        }
        Set<Long> userIds = new HashSet<>();
        for (Block card : cards) {
            Object value = properties(card).get(memberPropId);
            if (value != null) {
                userIds.add(Long.valueOf(value.toString()));
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nicknames = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            nicknames.put(user.getId(), user.getNickname());
        }
        return nicknames;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Block card) {
        if (card.getFields() == null) {
            return Map.of();
        }
        Object properties = card.getFields().get("properties");
        return properties instanceof Map ? (Map<String, Object>) properties : Map.of();
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /** 日期输出仅取前 10 位 YYYY-MM-DD（兼容存量带时间的值） */
    private String dateText(String value) {
        return value != null && value.length() >= 10 ? value.substring(0, 10) : null;
    }

    private byte[] toBytes(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
