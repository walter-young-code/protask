package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.common.SqlUtil;
import com.protask.dto.DashboardTaskItemVO;
import com.protask.dto.DashboardVO;
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
import com.protask.permission.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
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
    private final PermissionService permissionService;

    /**
     * 查询项目下的任务列表（需查看权限）。
     */
    public List<TaskVO> listTasks(Long userId, Long projectId) {
        checkProjectExists(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt)
                .orderByDesc(Block::getCreateAt));
        Map<Long, String> nicknames = nicknameMap(cards);
        List<TaskVO> vos = new ArrayList<>();
        for (Block card : cards) {
            vos.add(toVO(card, nicknames));
        }
        return vos;
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
        List<TaskVO> vos = new ArrayList<>();
        for (Block card : cards) {
            TaskVO vo = toVO(card, nicknames);
            vo.setProjectName(projectNames.get(card.getProjectId()));
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 跨项目仪表盘：在当前用户可见项目内聚合任务统计与分类列表。
     * 统计口径基于内置属性 id 约定：status（done=已完成）、priority（p0=紧急）、
     * dueDate（早于今天且未完成=逾期；今天起 7 天内且未完成=即将到期），
     * 我参与 = 我创建 或 项目负责人（模板中第一个 member 类型属性）指向我。
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
            boolean isMine = userId.equals(card.getCreatedBy())
                    || (scope.memberPropId() != null
                    && userId.toString().equals(asString(properties.get(scope.memberPropId()))));

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
        // 逾期/即将到期按截止日期升序（最紧迫在前），紧急/我参与按更新时间倒序
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
        card.setFields(Map.of(
                "icon", request.getIcon() != null ? request.getIcon() : "",
                "description", request.getDescription() != null ? request.getDescription() : "",
                "contentOrder", List.of(),
                "properties", request.getProperties() != null ? request.getProperties() : Map.of()));
        card.setCreatedBy(userId);
        card.setModifiedBy(userId);
        blockMapper.insert(card);
        return toVO(card, nicknameMap(List.of(card)));
    }

    /**
     * 更新任务卡片（需卡片管理权限），properties 整体替换。
     */
    @Transactional(rollbackFor = Exception.class)
    public TaskVO updateTask(Long userId, Long taskId, TaskRequest request) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);

        card.setTitle(request.getTitle());
        Map<String, Object> fields = new HashMap<>(card.getFields() != null
                ? card.getFields() : Map.of());
        // icon 为 null 表示不修改（其余字段照常整体替换）
        if (request.getIcon() != null) {
            fields.put("icon", request.getIcon());
        }
        fields.put("description", request.getDescription() != null ? request.getDescription() : "");
        fields.put("properties", request.getProperties() != null ? request.getProperties() : Map.of());
        card.setFields(fields);
        card.setModifiedBy(userId);
        blockMapper.updateById(card);
        return toVO(card, nicknameMap(List.of(card)));
    }

    /**
     * 软删除任务卡片（需卡片管理权限）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(Long userId, Long taskId) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(),
                ProjectPermission.MANAGE_PROJECT_CARDS);
        card.setDeleteAt(LocalDateTime.now());
        card.setModifiedBy(userId);
        blockMapper.updateById(card);
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
     * card 块转任务视图对象，补充创建人昵称。
     */
    private TaskVO toVO(Block card, Map<Long, String> nicknames) {
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
        return vo;
    }
}
