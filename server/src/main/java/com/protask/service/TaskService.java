package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.MessageUtil;
import com.protask.common.ResultCode;
import com.protask.common.SqlUtil;
import com.protask.dto.DashboardTaskItemVO;
import com.protask.dto.DashboardVO;
import com.protask.dto.ProjectVO;
import com.protask.dto.TaskRequest;
import com.protask.dto.TaskVO;
import com.protask.entity.Attachment;
import com.protask.entity.Block;
import com.protask.entity.Project;
import com.protask.entity.User;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 任务服务：任务即 type=card 的块，属性全部存于 fields.properties（动态定义）。
 * 读需 VIEW_PROJECT，写需 MANAGE_PROJECT_CARDS。
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    /** 全局搜索最大返回条数 */
    private static final int SEARCH_LIMIT = 20;

    /** 仪表盘分类列表最大返回条数 */
    private static final int DASHBOARD_LIST_LIMIT = 20;

    /** 即将到期窗口（天）：今天起 7 天内截止 */
    private static final long DUE_SOON_DAYS = 7;

    /** 任务标题最大长度（与 TaskRequest 校验一致） */
    private static final int MAX_TITLE_LENGTH = 200;

    /** 内置属性 id 约定（对齐前端 BUILTIN_PROPERTIES，仅模板含对应 id 的项目参与该维度统计） */
    private static final String PROP_STATUS = "status";
    private static final String PROP_PRIORITY = "priority";
    private static final String PROP_DUE_DATE = "dueDate";

    /** 内置状态“已完成”选项 id */
    private static final String STATUS_DONE = "done";

    /** 内置优先级“紧急”选项 id（P0） */
    private static final String PRIORITY_URGENT = "p0";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BlockMapper blockMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final AttachmentMapper attachmentMapper;
    private final PermissionService permissionService;
    private final GanttProjectService ganttProjectService;

    /**
     * 查询项目下的任务列表（需查看权限）。
     * WBS 项目按树序返回并附 WBS 编码/子任务数；非 WBS 项目保持创建时间倒序的扁平列表。
     */
    public List<TaskVO> listTasks(Long userId, Long projectId) {
        checkProjectExists(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        boolean wbsEnabled = ganttProjectService.hasWbs(projectId);
        List<Block> cards;
        GanttProjectService.TaskTree tree = null;
        if (wbsEnabled) {
            tree = ganttProjectService.buildTree(projectId);
            cards = tree.ordered();
        } else {
            cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                    .eq(Block::getProjectId, projectId)
                    .eq(Block::getType, BlockTypes.CARD)
                    .isNull(Block::getDeleteAt)
                    .orderByDesc(Block::getCreateAt));
        }
        Map<Long, String> nicknames = nicknameMap(cards);
        Map<Long, Long> attachmentCounts = attachmentCountMap(cards.stream().map(Block::getId).toList());
        List<TaskVO> vos = new ArrayList<>();
        for (Block card : cards) {
            TaskVO vo = toVO(card, nicknames, attachmentCounts.getOrDefault(card.getId(), 0L));
            if (wbsEnabled && tree != null) {
                vo.setParentTaskId(card.getParentTaskId());
                vo.setWbs(tree.wbs().get(card.getId()));
                vo.setChildCount(tree.childCount().getOrDefault(card.getId(), 0));
            }
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 同级重排（上移/下移）：仅 WBS 项目；先归一化同级序号再交换位置，WBS 随树序重编号。
     */
    @Transactional(rollbackFor = Exception.class)
    public void moveTask(Long userId, Long taskId, String direction) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);
        ganttProjectService.requireWbs(card.getProjectId());
        List<Block> siblings = ganttProjectService.liveCards(card.getProjectId()).stream()
                .filter(c -> Objects.equals(c.getParentTaskId(), card.getParentTaskId()))
                .sorted(Comparator
                        .comparing((Block b) -> b.getSortOrder() == null ? 0 : b.getSortOrder())
                        .thenComparing(Block::getCreateAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Block::getId))
                .collect(Collectors.toList());
        int index = -1;
        for (int i = 0; i < siblings.size(); i++) {
            if (siblings.get(i).getId().equals(taskId)) {
                index = i;
                break;
            }
        }
        int target = "up".equals(direction) ? index - 1 : index + 1;
        if (index < 0 || target < 0 || target >= siblings.size()) {
            return;
        }
        // 归一化序号后交换，避免存量 sortOrder 全 0 时交换无效
        for (int i = 0; i < siblings.size(); i++) {
            siblings.get(i).setSortOrder(i + 1);
        }
        Block moved = siblings.get(index);
        Block neighbor = siblings.get(target);
        int tmp = moved.getSortOrder();
        moved.setSortOrder(neighbor.getSortOrder());
        neighbor.setSortOrder(tmp);
        moved.setModifiedBy(userId);
        neighbor.setModifiedBy(userId);
        blockMapper.updateById(moved);
        blockMapper.updateById(neighbor);
    }

    /**
     * 全局搜索：在当前用户可见项目（参与的 + 公开的，系统管理员为全部）内
     * 按标题/描述模糊匹配卡片，按更新时间倒序，最多返回 SEARCH_LIMIT 条。
     */
    public List<TaskVO> searchTasks(Long userId, String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        List<Long> projectIds = searchableProjectIds(userId);
        if (projectIds.isEmpty()) {
            return List.of();
        }
        // 转义 LIKE 通配符，避免用户输入 %/_ 扩大匹配范围
        List<Block> cards = blockMapper.searchCards(SqlUtil.escapeLike(kw), projectIds, SEARCH_LIMIT);
        if (cards.isEmpty()) {
            return List.of();
        }
        Map<Long, String> projectNames = projectMapper.selectBatchIds(cards.stream()
                        .map(Block::getProjectId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Project::getId, Project::getName));
        Map<Long, String> nicknames = nicknameMap(cards);
        Map<Long, Long> attachmentCounts = attachmentCountMap(cards.stream().map(Block::getId).toList());
        List<TaskVO> vos = new ArrayList<>();
        for (Block card : cards) {
            TaskVO vo = toVO(card, nicknames, attachmentCounts.getOrDefault(card.getId(), 0L));
            vo.setProjectName(projectNames.get(card.getProjectId()));
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 跨项目仪表盘：在当前用户可见项目内聚合任务统计与分类列表。
     * 统计口径基于内置属性 id 约定：status（done=已完成）、priority（p0=紧急）、
     * dueDate（早于今天且未完成=逾期；今天起 7 天内且未完成=即将到期），
     * 我负责 = 项目负责人（模板中第一个 member 类型属性）指向我且未完成。
     * 项目未添加对应内置属性时不参与该维度统计；用户自定义的同类型属性不计入。
     */
    public DashboardVO getDashboard(Long userId) {
        List<Project> projects = visibleProjects(userId);
        DashboardVO vo = new DashboardVO();
        if (projects.isEmpty()) {
            return vo;
        }
        Map<Long, Project> projectById = projects.stream()
                .collect(Collectors.toMap(Project::getId, p -> p));
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .in(Block::getProjectId, projectById.keySet())
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt));
        Map<Long, String> nicknames = nicknameMap(cards);
        Map<Long, PropertyScope> scopeCache = new HashMap<>();
        LocalDate today = LocalDate.now();

        long my = 0;
        long urgent = 0;
        long overdue = 0;
        long dueSoon = 0;
        long done = 0;
        List<DashboardTaskItemVO> myList = new ArrayList<>();
        List<DashboardTaskItemVO> urgentList = new ArrayList<>();
        List<DashboardTaskItemVO> overdueList = new ArrayList<>();
        List<DashboardTaskItemVO> dueSoonList = new ArrayList<>();

        for (Block card : cards) {
            Project project = projectById.get(card.getProjectId());
            PropertyScope scope = scopeCache.computeIfAbsent(card.getProjectId(),
                    id -> parseScope(project.getCardProperties()));
            Map<String, Object> properties = currentProperties(card);

            boolean isDone = scope.hasStatus() && STATUS_DONE.equals(asString(properties.get(PROP_STATUS)));
            if (isDone) {
                done++;
            }
            LocalDate due = scope.hasDueDate() ? parseDate(asString(properties.get(PROP_DUE_DATE))) : null;
            boolean isOverdue = !isDone && due != null && due.isBefore(today);
            boolean isDueSoon = !isDone && due != null
                    && !due.isBefore(today) && !due.isAfter(today.plusDays(DUE_SOON_DAYS));
            boolean isUrgent = scope.hasPriority() && PRIORITY_URGENT.equals(asString(properties.get(PROP_PRIORITY)));
            boolean isMine = !isDone && scope.memberPropId() != null
                    && userId.toString().equals(asString(properties.get(scope.memberPropId())));

            if (isMine) {
                my++;
                myList.add(toItem(card, project.getName(), due, nicknames));
            }
            if (isUrgent) {
                urgent++;
                urgentList.add(toItem(card, project.getName(), due, nicknames));
            }
            if (isOverdue) {
                overdue++;
                overdueList.add(toItem(card, project.getName(), due, nicknames));
            }
            if (isDueSoon) {
                dueSoon++;
                dueSoonList.add(toItem(card, project.getName(), due, nicknames));
            }
        }

        vo.setTotalTasks(cards.size());
        vo.setMyTasks(my);
        vo.setUrgentTasks(urgent);
        vo.setOverdueTasks(overdue);
        vo.setDueSoonTasks(dueSoon);
        vo.setDoneTasks(done);
        // 逾期/即将到期按截止日期升序（最紧迫在前），紧急/我负责按更新时间倒序
        Comparator<DashboardTaskItemVO> byDueDateAsc = Comparator.comparing(DashboardTaskItemVO::getDueDate);
        Comparator<DashboardTaskItemVO> byUpdatedDesc = Comparator.comparing(
                DashboardTaskItemVO::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
        vo.setOverdueList(top(overdueList, byDueDateAsc));
        vo.setDueSoonList(top(dueSoonList, byDueDateAsc));
        vo.setUrgentList(top(urgentList, byUpdatedDesc));
        vo.setMyTaskList(top(myList, byUpdatedDesc));
        return vo;
    }

    /** 列表按指定顺序排序后截断到 DASHBOARD_LIST_LIMIT 条 */
    private List<DashboardTaskItemVO> top(List<DashboardTaskItemVO> list,
                                          Comparator<DashboardTaskItemVO> comparator) {
        return list.stream().sorted(comparator).limit(DASHBOARD_LIST_LIMIT).toList();
    }

    /**
     * 仪表盘可见项目范围：系统管理员为全部非模板的正常项目，
     * 其余为参与的项目（与全局搜索范围一致，额外排除归档项目）。
     */
    private List<Project> visibleProjects(Long userId) {
        User user = userMapper.selectById(userId);
        if (user != null && "admin".equals(user.getRole())) {
            return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .eq(Project::getIsTemplate, false)
                    .eq(Project::getStatus, "active")
                    .isNull(Project::getDeleteAt));
        }
        return projectMapper.selectMyProjects(userId).stream().map(pvo -> {
            Project project = new Project();
            project.setId(pvo.getId());
            project.setName(pvo.getName());
            project.setCardProperties(pvo.getCardProperties());
            return project;
        }).toList();
    }

    /** 项目属性模板解析结果：是否含内置属性 + 负责人（第一个 member 类型属性）属性 id */
    private record PropertyScope(boolean hasStatus, boolean hasPriority, boolean hasDueDate,
                                 String memberPropId) {
    }

    /** 解析项目 cardProperties JSON 数组；空或非法 JSON 视为无任何内置属性 */
    private PropertyScope parseScope(String cardPropertiesJson) {
        if (cardPropertiesJson == null || cardPropertiesJson.isBlank()) {
            return new PropertyScope(false, false, false, null);
        }
        boolean hasStatus = false;
        boolean hasPriority = false;
        boolean hasDueDate = false;
        String memberPropId = null;
        try {
            JsonNode root = OBJECT_MAPPER.readTree(cardPropertiesJson);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String id = node.path("id").asText(null);
                    String type = node.path("type").asText(null);
                    if (PROP_STATUS.equals(id)) {
                        hasStatus = true;
                    } else if (PROP_PRIORITY.equals(id)) {
                        hasPriority = true;
                    } else if (PROP_DUE_DATE.equals(id)) {
                        hasDueDate = true;
                    }
                    if (memberPropId == null && "member".equals(type)) {
                        memberPropId = id;
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // 非法 JSON 按无内置属性处理，不影响聚合
        }
        return new PropertyScope(hasStatus, hasPriority, hasDueDate, memberPropId);
    }

    /** 解析截止日期（仅取前 10 位 YYYY-MM-DD，兼容存量带时间的值），非法返回 null */
    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** card 块转仪表盘列表项 */
    private DashboardTaskItemVO toItem(Block card, String projectName, LocalDate dueDate,
                                       Map<Long, String> nicknames) {
        DashboardTaskItemVO item = new DashboardTaskItemVO();
        item.setId(card.getId());
        item.setProjectId(card.getProjectId());
        item.setProjectName(projectName);
        item.setTitle(card.getTitle());
        item.setDueDate(dueDate != null ? dueDate.toString() : null);
        item.setUpdatedAt(card.getUpdateAt());
        item.setCreatorName(nicknames.get(card.getCreatedBy()));
        if (card.getFields() != null) {
            item.setIcon(asString(card.getFields().get("icon")));
        }
        return item;
    }

    /**
     * 计算用户可搜索的项目范围：系统管理员为全部项目，
     * 其余为参与的项目（公开项目仅凭分享链接访问，不进入全局搜索）。
     * 模板项目不在常规界面展示且详情接口拒绝访问，必须排除，
     * 否则搜索结果会出现用户看不到的“不存在项目”。
     */
    private List<Long> searchableProjectIds(Long userId) {
        User user = userMapper.selectById(userId);
        if (user != null && "admin".equals(user.getRole())) {
            return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                    .eq(Project::getIsTemplate, false)
                    .isNull(Project::getDeleteAt)).stream()
                    .map(Project::getId).toList();
        }
        return projectMapper.selectMyProjects(userId).stream()
                .map(ProjectVO::getId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 创建任务卡片（需卡片管理权限），属性完全由请求携带。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskVO createTask(Long userId, Long projectId, TaskRequest request) {
        checkProjectExists(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_CARDS);

        Block card = new Block();
        card.setProjectId(projectId);
        card.setSchemaVer(1L);
        card.setType(BlockTypes.CARD);
        card.setTitle(request.getTitle());
        card.setMilestone(false);
        card.setFields(Map.of(
                "icon", request.getIcon() != null ? request.getIcon() : "",
                "description", request.getDescription() != null ? request.getDescription() : "",
                "contentOrder", List.of(),
                "properties", request.getProperties() != null ? request.getProperties() : Map.of()));
        // 父子层级仅 WBS 项目生效（防环校验）；非 WBS 项目忽略父任务请求
        if (ganttProjectService.hasWbs(projectId) && request.getParentTaskId() != null) {
            ganttProjectService.validateParent(projectId, null, request.getParentTaskId());
            card.setParentTaskId(request.getParentTaskId());
            card.setSortOrder(ganttProjectService.nextSortOrder(projectId, request.getParentTaskId()));
        }
        card.setCreatedBy(userId);
        card.setModifiedBy(userId);
        blockMapper.insert(card);
        return toVO(card, nicknameMap(List.of(card)), 0L);
    }

    /**
     * 复制任务卡片（同项目内），仅复制卡片内容：标题加副本后缀、图标、描述、动态属性。
     * 不复制评论与附件；需卡片管理权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskVO copyTask(Long userId, Long taskId) {
        Block source = getCard(taskId);
        permissionService.checkPermission(userId, source.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);

        Block copy = new Block();
        copy.setProjectId(source.getProjectId());
        copy.setSchemaVer(source.getSchemaVer());
        copy.setType(BlockTypes.CARD);
        copy.setTitle(resolveCopyTitle(source.getTitle()));
        copy.setMilestone(source.getMilestone());
        // 计划模式/工期随副本保留；开始约束不复制（副本以自身日期为锚点）
        copy.setScheduleMode(source.getScheduleMode());
        copy.setDurationDays(source.getDurationDays());
        Map<String, Object> sourceFields = source.getFields() != null
                ? source.getFields() : Map.of();
        Map<String, Object> fields = new HashMap<>(sourceFields);
        // properties 嵌套 map 单独复制，避免与源卡片共享引用
        if (sourceFields.get("properties") instanceof Map<?, ?> properties) {
            fields.put("properties", new HashMap<>(properties));
        }
        copy.setFields(fields);
        // WBS 项目副本保留层级：挂到源任务同父下并排到同级末尾
        if (ganttProjectService.hasWbs(source.getProjectId())) {
            copy.setParentTaskId(source.getParentTaskId());
            copy.setSortOrder(ganttProjectService.nextSortOrder(source.getProjectId(), source.getParentTaskId()));
        }
        copy.setCreatedBy(userId);
        copy.setModifiedBy(userId);
        blockMapper.insert(copy);
        // 附件不随任务复制，副本附件数为 0
        return toVO(copy, nicknameMap(List.of(copy)), 0L);
    }

    /** 副本标题：源标题 + 后缀；源标题过长时截断源标题，保证总长不超过 200 且后缀完整 */
    private String resolveCopyTitle(String sourceTitle) {
        String suffix = MessageUtil.get("task.copySuffix");
        String base = sourceTitle != null ? sourceTitle : "";
        int maxBase = MAX_TITLE_LENGTH - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        return base + suffix;
    }

    /**
     * 更新任务卡片（需卡片管理权限），properties 整体替换。
     * WBS 项目额外支持计划模式/工期/约束设置，保存后触发自动排程传播。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskVO updateTask(Long userId, Long taskId, TaskRequest request) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);

        Map<String, Object> oldProps = currentProperties(card);
        card.setTitle(request.getTitle());
        Map<String, Object> fields = new HashMap<>(card.getFields() != null
                ? card.getFields() : Map.of());
        // icon 为 null 表示不修改（其余字段照常整体替换）
        if (request.getIcon() != null) {
            fields.put("icon", request.getIcon());
        }
        fields.put("description", request.getDescription() != null ? request.getDescription() : "");
        Map<String, Object> newProps = new HashMap<>(request.getProperties() != null
                ? request.getProperties() : Map.of());
        fields.put("properties", newProps);
        card.setFields(fields);
        boolean wbs = ganttProjectService.hasWbs(card.getProjectId());
        if (wbs) {
            applyScheduleSettings(card, request, oldProps, newProps);
        }
        // 里程碑约束：仅起止同日可标记（跨日/未排期拒绝）；日期改为跨日时自动清除既有标记
        boolean sameDay = sameDayScheduled(fields);
        if (request.getMilestone() != null) {
            if (request.getMilestone() && !sameDay) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "gantt.milestoneSameDayRequired");
            }
            card.setMilestone(request.getMilestone());
        } else if (Boolean.TRUE.equals(card.getMilestone()) && !sameDay) {
            card.setMilestone(false);
        }
        // 父任务变更仅 WBS 项目生效：null=不修改，0=设为根，其余=指定父（防环校验）
        if (ganttProjectService.hasWbs(card.getProjectId()) && request.getParentTaskId() != null) {
            Long parentId = request.getParentTaskId() == 0L ? null : request.getParentTaskId();
            ganttProjectService.validateParent(card.getProjectId(), taskId, parentId);
            if (!Objects.equals(parentId, card.getParentTaskId())) {
                card.setParentTaskId(parentId);
                card.setSortOrder(ganttProjectService.nextSortOrder(card.getProjectId(), parentId));
            }
        }
        card.setModifiedBy(userId);
        blockMapper.updateById(card);
        if (wbs) {
            ganttProjectService.rescheduleAutoTasks(card.getProjectId());
            // 引擎可能回写本任务日期（自动任务约束吸附），重读保证 VO 一致
            card = blockMapper.selectById(taskId);
        }
        return toVO(card, nicknameMap(List.of(card)),
                attachmentCountMap(List.of(taskId)).getOrDefault(taskId, 0L));
    }

    /**
     * 排程设置（仅 WBS 项目）：计划模式/显式工期/清除约束，及自动任务日期编辑解释——
     * 开始变更=设「不得早于」约束，截止变更=推导工期（两规则独立：
     * 整体拖拽移动=设约束且工期不变；右端拉伸=仅改工期不钉住开始）。
     */
    private void applyScheduleSettings(Block card, TaskRequest request,
                                       Map<String, Object> oldProps, Map<String, Object> newProps) {
        if (request.getScheduleMode() != null) {
            String mode = request.getScheduleMode();
            if (!GanttProjectService.MODE_AUTO.equals(mode) && !GanttProjectService.MODE_MANUAL.equals(mode)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "task.scheduleModeInvalid");
            }
            card.setScheduleMode(mode);
        }
        if (Boolean.TRUE.equals(request.getClearConstraint())) {
            card.setStartConstraint(null);
        }
        if (GanttProjectService.MODE_AUTO.equals(card.getScheduleMode())) {
            String oldStart = dateKey(oldProps.get(GanttProjectService.PROP_START_DATE));
            String oldDue = dateKey(oldProps.get(GanttProjectService.PROP_DUE_DATE));
            LocalDate newStart = parseDateKey(dateKey(newProps.get(GanttProjectService.PROP_START_DATE)));
            LocalDate newDue = parseDateKey(dateKey(newProps.get(GanttProjectService.PROP_DUE_DATE)));
            String newStartKey = dateKey(newProps.get(GanttProjectService.PROP_START_DATE));
            String newDueKey = dateKey(newProps.get(GanttProjectService.PROP_DUE_DATE));
            if (newStart != null && !newStartKey.equals(oldStart)) {
                card.setStartConstraint(newStart);
            }
            if (newStart != null && newDue != null && !newDueKey.equals(oldDue)) {
                long days = ChronoUnit.DAYS.between(newStart, newDue) + 1;
                card.setDurationDays((int) Math.max(1, Math.min(days, GanttProjectService.DURATION_LIMIT)));
            }
        }
        if (request.getDurationDays() != null) {
            int duration = request.getDurationDays();
            if (duration < 1 || duration > GanttProjectService.DURATION_LIMIT) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "task.durationInvalid");
            }
            // 里程碑工期恒 0，忽略显式工期
            if (!Boolean.TRUE.equals(card.getMilestone())) {
                card.setDurationDays(duration);
                // 手动任务：同步截止 = 开始 + 工期 - 1（自动任务由引擎推导）
                if (!GanttProjectService.MODE_AUTO.equals(card.getScheduleMode())) {
                    LocalDate start = parseDateKey(dateKey(newProps.get(GanttProjectService.PROP_START_DATE)));
                    if (start != null) {
                        newProps.put(GanttProjectService.PROP_DUE_DATE, start.plusDays(duration - 1L).toString());
                    }
                }
            }
        }
    }

    /** 解析日期键（非法值视为 null，不阻断保存） */
    private LocalDate parseDateKey(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 软删除任务卡片（需卡片管理权限）。
     * WBS 项目级联软删全部子孙并清理涉及它们的依赖行。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long userId, Long taskId) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);
        Set<Long> deletedIds = new java.util.HashSet<>();
        deletedIds.add(taskId);
        if (ganttProjectService.hasWbs(card.getProjectId())) {
            deletedIds.addAll(ganttProjectService.descendantIds(card.getProjectId(), taskId));
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long id : deletedIds) {
            Block target = id.equals(taskId) ? card : blockMapper.selectById(id);
            if (target == null || target.getDeleteAt() != null) {
                continue;
            }
            target.setDeleteAt(now);
            target.setModifiedBy(userId);
            blockMapper.updateById(target);
        }
        ganttProjectService.deleteDependenciesOfTasks(card.getProjectId(), deletedIds);
        if (ganttProjectService.hasWbs(card.getProjectId())) {
            // 依赖移除后自动后置可能吸附到约束/锚点，重跑排程
            ganttProjectService.rescheduleAutoTasks(card.getProjectId());
        }
    }

    /**
     * 查询任务卡片（未删除的 card 块）。
     */
    private Block getCard(Long taskId) {
        Block card = blockMapper.selectById(taskId);
        if (card == null || card.getDeleteAt() != null || !BlockTypes.CARD.equals(card.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "task.notFound");
        }
        return card;
    }

    private void checkProjectExists(Long projectId) {
        if (projectMapper.selectById(projectId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.notFound");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> currentProperties(Block card) {
        if (card.getFields() == null) {
            return Map.of();
        }
        Object properties = card.getFields().get("properties");
        return properties instanceof Map ? (Map<String, Object>) properties : Map.of();
    }

    private String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    /** 批量查询卡片创建人昵称（避免逐卡查询的 N+1） */
    private Map<Long, String> nicknameMap(List<Block> cards) {
        Set<Long> userIds = cards.stream()
                .map(Block::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
    }

    /**
     * card 块转任务视图对象，补充创建人昵称与附件数量。
     */
    private TaskVO toVO(Block card, Map<Long, String> nicknames, long attachmentCount) {
        TaskVO vo = new TaskVO();
        vo.setId(card.getId());
        vo.setProjectId(card.getProjectId());
        vo.setTitle(card.getTitle());
        vo.setCreatorId(card.getCreatedBy());
        vo.setCreatedAt(card.getCreateAt());
        vo.setUpdatedAt(card.getUpdateAt());
        vo.setProperties(new HashMap<>(currentProperties(card)));
        if (card.getFields() != null) {
            vo.setIcon(asString(card.getFields().get("icon")));
            vo.setDescription(asString(card.getFields().get("description")));
        }
        vo.setCreatorName(nicknames.get(card.getCreatedBy()));
        vo.setAttachmentCount(attachmentCount);
        vo.setMilestone(card.getMilestone());
        vo.setScheduleMode(card.getScheduleMode() == null ? GanttProjectService.MODE_MANUAL : card.getScheduleMode());
        vo.setDurationDays(card.getDurationDays());
        vo.setStartConstraint(card.getStartConstraint());
        return vo;
    }

    /** 起止日期是否同日（里程碑标记约束；未排期视为非同日） */
    private boolean sameDayScheduled(Map<String, Object> fields) {
        if (!(fields.get("properties") instanceof Map<?, ?> props)) {
            return false;
        }
        String start = dateKey(props.get(GanttProjectService.PROP_START_DATE));
        String end = dateKey(props.get(GanttProjectService.PROP_DUE_DATE));
        return start != null && start.equals(end);
    }

    /** 日期值仅取前 10 位 YYYY-MM-DD（兼容存量带时间的值） */
    private String dateKey(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString();
        return s.length() >= 10 ? s.substring(0, 10) : null;
    }

    /**
     * 批量统计各任务附件数（任务 id -> 附件数），避免逐任务查询的 N+1。
     */
    private Map<Long, Long> attachmentCountMap(List<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> rows = attachmentMapper.selectMaps(
                new QueryWrapper<Attachment>()
                        .select("task_id AS taskId", "COUNT(*) AS cnt")
                        .in("task_id", taskIds)
                        .groupBy("task_id"));
        Map<Long, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(((Number) row.get("taskId")).longValue(),
                    ((Number) row.get("cnt")).longValue());
        }
        return counts;
    }
}
