package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.CardPropertiesRequest;
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
import com.protask.permission.ProjectPermission;
import lombok.RequiredArgsConstructor;
import net.sf.mpxj.ConstraintType;
import net.sf.mpxj.Duration;
import net.sf.mpxj.ProjectCalendar;
import net.sf.mpxj.ProjectCalendarHours;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.Relation;
import net.sf.mpxj.RelationType;
import net.sf.mpxj.Resource;
import net.sf.mpxj.ResourceAssignment;
import net.sf.mpxj.Task;
import net.sf.mpxj.TaskMode;
import net.sf.mpxj.TimeUnit;
import net.sf.mpxj.mspdi.MSPDIWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WBS 项目增强服务：门禁（项目属性模板含内置 wbs 属性才启用）、
 * 树序编号、父子防环、四类型依赖管理（FS/SS/FF/SF，DFS 环检测）与 MS Project(MSPDI) 导出。
 * 未添加 WBS 编码属性的项目不调用本服务的写路径，行为与升级前完全一致。
 */
@Service
@RequiredArgsConstructor
public class GanttProjectService {

    /** 内置 WBS 编码属性 id（对齐前端 BUILTIN_PROPERTIES） */
    public static final String PROP_WBS = "wbs";

    /** 内置开始/截止日期属性 id（甘特排期字段，与 WBS 绑定启用） */
    public static final String PROP_START_DATE = "startDate";
    public static final String PROP_DUE_DATE = "dueDate";

    /** 甘特必要字段：启用（含 WBS）后禁止删除 */
    public static final Set<String> REQUIRED_PROPS = Set.of(PROP_WBS, PROP_START_DATE, PROP_DUE_DATE);

    /** 支持的依赖类型（对齐 MS Project 四种链接类型） */
    public static final Set<String> DEPENDENCY_TYPES = Set.of("FS", "SS", "FF", "SF");

    /** 延迟天数上限（负数=提前量，自然日粒度） */
    private static final int LAG_LIMIT = 365;

    /** 工期上限（自然日含首日） */
    public static final int DURATION_LIMIT = 3650;

    /** 计划模式：自动（前置驱动排程）/ 手动（日期自由编辑） */
    public static final String MODE_AUTO = "auto";
    public static final String MODE_MANUAL = "manual";

    /** 依赖类型 → MPXJ 链接类型映射（MSPDI 导出用） */
    private static final Map<String, RelationType> RELATION_TYPES = Map.of(
            "FS", RelationType.FINISH_START,
            "SS", RelationType.START_START,
            "FF", RelationType.FINISH_FINISH,
            "SF", RelationType.START_FINISH);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final BlockMapper blockMapper;
    private final ProjectMapper projectMapper;
    private final UserMapper userMapper;
    private final TaskDependencyMapper dependencyMapper;
    private final PermissionService permissionService;

    /** 树序结果：DFS 顺序 + 每任务 WBS 编码 / 直接子任务数 / 层级深度 */
    public record TaskTree(List<Block> ordered, Map<Long, String> wbs,
                           Map<Long, Integer> childCount, Map<Long, Integer> depth) {
    }

    /** 项目是否添加「WBS 编码」属性（门禁判定） */
    public boolean hasWbs(Project project) {
        if (project == null || project.getCardProperties() == null) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(project.getCardProperties());
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if (PROP_WBS.equals(node.path("id").asText(null))) {
                        return true;
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // 非法 JSON 视为未添加
        }
        return false;
    }

    public boolean hasWbs(Long projectId) {
        return hasWbs(projectMapper.selectById(projectId));
    }

    /** 门禁校验：非 WBS 项目拒绝甘特/依赖/导出/重排操作 */
    public void requireWbs(Long projectId) {
        if (!hasWbs(projectId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "gantt.wbsRequired");
        }
    }

    /** 必要字段保护：已启用甘特（含 WBS）的项目禁止删除 WBS/开始日期/截止日期 */
    public void requireRequiredFieldsKept(Project project, List<String> deletedIds) {
        if (!hasWbs(project)) {
            return;
        }
        for (String id : deletedIds) {
            if (REQUIRED_PROPS.contains(id)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "gantt.requiredFieldsLocked");
            }
        }
    }

    /**
     * 启用绑定：新模板含 WBS 时自动补齐内置开始/截止日期属性（存量项目下次保存时自愈），
     * 甘特排期固定读取这两个内置字段，视图不再提供日期属性选择。
     */
    public List<CardPropertiesRequest.CardPropertyDef> ensureScheduleFields(
            List<CardPropertiesRequest.CardPropertyDef> properties) {
        boolean hasWbs = properties.stream().anyMatch(p -> PROP_WBS.equals(p.getId()));
        if (!hasWbs) {
            return properties;
        }
        List<CardPropertiesRequest.CardPropertyDef> result = new ArrayList<>(properties);
        if (result.stream().noneMatch(p -> PROP_START_DATE.equals(p.getId()))) {
            result.add(scheduleDef(PROP_START_DATE, "开始日期"));
        }
        if (result.stream().noneMatch(p -> PROP_DUE_DATE.equals(p.getId()))) {
            result.add(scheduleDef(PROP_DUE_DATE, "截止日期"));
        }
        return result;
    }

    private CardPropertiesRequest.CardPropertyDef scheduleDef(String id, String name) {
        CardPropertiesRequest.CardPropertyDef def = new CardPropertiesRequest.CardPropertyDef();
        def.setId(id);
        def.setName(name);
        def.setType("date");
        return def;
    }

    /** 项目下未删除的任务卡片 */
    public List<Block> liveCards(Long projectId) {
        return blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt));
    }

    /**
     * 树序编号：同级按 sortOrder → 创建时间 → id 排序后 DFS，
     * WBS 编码 1 / 1.1 / 1.2 只读推导；孤儿父引用提升为根。
     */
    public TaskTree buildTree(Long projectId) {
        List<Block> cards = liveCards(projectId);
        Set<Long> ids = cards.stream().map(Block::getId).collect(Collectors.toSet());
        Map<Long, List<Block>> children = new HashMap<>();
        List<Block> roots = new ArrayList<>();
        for (Block card : cards) {
            Long parentId = card.getParentTaskId();
            if (parentId == null || !ids.contains(parentId)) {
                roots.add(card);
            } else {
                children.computeIfAbsent(parentId, k -> new ArrayList<>()).add(card);
            }
        }
        Comparator<Block> siblingOrder = Comparator
                .comparing((Block b) -> b.getSortOrder() == null ? 0 : b.getSortOrder())
                .thenComparing(Block::getCreateAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Block::getId);
        roots.sort(siblingOrder);
        children.values().forEach(list -> list.sort(siblingOrder));

        List<Block> ordered = new ArrayList<>();
        Map<Long, String> wbs = new HashMap<>();
        Map<Long, Integer> childCount = new HashMap<>();
        Map<Long, Integer> depth = new HashMap<>();
        Deque<Object[]> stack = new ArrayDeque<>();
        // 逆序入栈保证 DFS 输出与同级排序一致
        for (int i = roots.size() - 1; i >= 0; i--) {
            stack.push(new Object[]{roots.get(i), String.valueOf(i + 1), 0});
        }
        while (!stack.isEmpty()) {
            Object[] item = stack.pop();
            Block card = (Block) item[0];
            String code = (String) item[1];
            int level = (Integer) item[2];
            ordered.add(card);
            wbs.put(card.getId(), code);
            depth.put(card.getId(), level);
            List<Block> kids = children.getOrDefault(card.getId(), List.of());
            childCount.put(card.getId(), kids.size());
            for (int i = kids.size() - 1; i >= 0; i--) {
                stack.push(new Object[]{kids.get(i), code + "." + (i + 1), level + 1});
            }
        }
        return new TaskTree(ordered, wbs, childCount, depth);
    }

    /** 任务的全部子孙 id（不含自身） */
    public Set<Long> descendantIds(Long projectId, Long taskId) {
        Map<Long, List<Block>> children = new HashMap<>();
        for (Block card : liveCards(projectId)) {
            if (card.getParentTaskId() != null) {
                children.computeIfAbsent(card.getParentTaskId(), k -> new ArrayList<>()).add(card);
            }
        }
        Set<Long> result = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(taskId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            for (Block kid : children.getOrDefault(current, List.of())) {
                if (result.add(kid.getId())) {
                    stack.push(kid.getId());
                }
            }
        }
        return result;
    }

    /** 父任务校验：同项目未删除卡片、非自身、非自身子孙（防环） */
    public void validateParent(Long projectId, Long taskId, Long parentId) {
        if (parentId == null) {
            return;
        }
        if (parentId.equals(taskId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "task.parentSelf");
        }
        Block parent = blockMapper.selectById(parentId);
        if (parent == null || parent.getDeleteAt() != null
                || !BlockTypes.CARD.equals(parent.getType())
                || !projectId.equals(parent.getProjectId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "task.parentInvalid");
        }
        if (taskId != null && descendantIds(projectId, taskId).contains(parentId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "task.parentCycle");
        }
    }

    /** 新任务的同级排序序号：同父兄弟最大 sortOrder + 1 */
    public int nextSortOrder(Long projectId, Long parentTaskId) {
        return liveCards(projectId).stream()
                .filter(c -> Objects.equals(c.getParentTaskId(), parentTaskId))
                .mapToInt(c -> c.getSortOrder() == null ? 0 : c.getSortOrder())
                .max().orElse(0) + 1;
    }

    // ---------- 四类型依赖管理（FS/SS/FF/SF） ----------

    /** 项目依赖列表（非 WBS 项目返回空列表） */
    public List<DependencyVO> listDependencies(Long userId, Long projectId) {
        checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        if (!hasWbs(projectId)) {
            return List.of();
        }
        return dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                        .eq(TaskDependency::getProjectId, projectId))
                .stream().map(this::toVO).toList();
    }

    /** 创建依赖：类型/lag 校验、自依赖/重复/环（DFS）拦截 */
    @Transactional(rollbackFor = Exception.class)
    public DependencyVO createDependency(Long userId, Long projectId, DependencyRequest request) {
        checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_CARDS);
        requireWbs(projectId);
        String type = normalizeType(request.getType());
        int lag = normalizeLag(request.getLag());
        Long predecessorId = request.getPredecessorId();
        Long successorId = request.getSuccessorId();
        if (predecessorId.equals(successorId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "dependency.self");
        }
        Set<Long> liveIds = liveCards(projectId).stream().map(Block::getId).collect(Collectors.toSet());
        if (!liveIds.contains(predecessorId) || !liveIds.contains(successorId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "task.notFound");
        }
        Long count = dependencyMapper.selectCount(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getPredecessorId, predecessorId)
                .eq(TaskDependency::getSuccessorId, successorId));
        if (count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "dependency.duplicate");
        }
        // 环检测：沿现有 pred→succ 边从 successor 出发 DFS，若能到达 predecessor 则成环。
        // 类型无关的保守策略（与 MS Project 拒绝任何环形链接一致）
        Map<Long, List<Long>> edges = new HashMap<>();
        for (TaskDependency dep : dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getProjectId, projectId))) {
            edges.computeIfAbsent(dep.getPredecessorId(), k -> new ArrayList<>()).add(dep.getSuccessorId());
        }
        Set<Long> visited = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(successorId);
        while (!stack.isEmpty()) {
            Long current = stack.pop();
            if (current.equals(predecessorId)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "dependency.cycle");
            }
            if (visited.add(current)) {
                for (Long next : edges.getOrDefault(current, List.of())) {
                    stack.push(next);
                }
            }
        }
        TaskDependency dep = new TaskDependency();
        dep.setProjectId(projectId);
        dep.setPredecessorId(predecessorId);
        dep.setSuccessorId(successorId);
        dep.setType(type);
        dep.setLag(lag);
        dep.setCreateAt(LocalDateTime.now());
        dependencyMapper.insert(dep);
        rescheduleAutoTasks(projectId);
        return toVO(dep);
    }

    /** 修改依赖类型/lag（边不变，无需重跑环检测） */
    @Transactional(rollbackFor = Exception.class)
    public DependencyVO updateDependency(Long userId, Long dependencyId, DependencyPatchRequest request) {
        TaskDependency dep = dependencyMapper.selectById(dependencyId);
        if (dep == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "dependency.notFound");
        }
        permissionService.checkPermission(userId, dep.getProjectId(), ProjectPermission.MANAGE_PROJECT_CARDS);
        requireWbs(dep.getProjectId());
        if (request.getType() != null) {
            dep.setType(normalizeType(request.getType()));
        }
        if (request.getLag() != null) {
            dep.setLag(normalizeLag(request.getLag()));
        }
        dependencyMapper.updateById(dep);
        rescheduleAutoTasks(dep.getProjectId());
        return toVO(dep);
    }

    /** 类型归一化：缺省 FS，非法类型拒绝 */
    private String normalizeType(String type) {
        String value = type == null ? "FS" : type;
        if (!DEPENDENCY_TYPES.contains(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "dependency.typeInvalid");
        }
        return value;
    }

    /** 延迟归一化：缺省 0，越界拒绝（负数=提前量） */
    private int normalizeLag(Integer lag) {
        int value = lag == null ? 0 : lag;
        if (value < -LAG_LIMIT || value > LAG_LIMIT) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "dependency.lagInvalid");
        }
        return value;
    }

    /** 删除依赖（需所属项目卡片管理权限） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDependency(Long userId, Long dependencyId) {
        TaskDependency dep = dependencyMapper.selectById(dependencyId);
        if (dep == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "dependency.notFound");
        }
        permissionService.checkPermission(userId, dep.getProjectId(), ProjectPermission.MANAGE_PROJECT_CARDS);
        requireWbs(dep.getProjectId());
        dependencyMapper.deleteById(dependencyId);
        rescheduleAutoTasks(dep.getProjectId());
    }

    /** 清理涉及指定任务的依赖行（任务级联删除时调用） */
    public void deleteDependenciesOfTasks(Long projectId, Set<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        dependencyMapper.delete(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getProjectId, projectId)
                .and(w -> w.in(TaskDependency::getPredecessorId, taskIds)
                        .or().in(TaskDependency::getSuccessorId, taskIds)));
    }

    /** 复制项目/模板时保留依赖（仅源项目为 WBS 项目时） */
    @Transactional(rollbackFor = Exception.class)
    public void copyDependencies(Long sourceProjectId, Long targetProjectId, Map<Long, Long> idMapping) {
        if (!hasWbs(sourceProjectId)) {
            return;
        }
        for (TaskDependency dep : dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getProjectId, sourceProjectId))) {
            Long predecessorId = idMapping.get(dep.getPredecessorId());
            Long successorId = idMapping.get(dep.getSuccessorId());
            if (predecessorId == null || successorId == null) {
                continue;
            }
            TaskDependency copy = new TaskDependency();
            copy.setProjectId(targetProjectId);
            copy.setPredecessorId(predecessorId);
            copy.setSuccessorId(successorId);
            copy.setType(dep.getType());
            copy.setLag(dep.getLag());
            copy.setCreateAt(LocalDateTime.now());
            dependencyMapper.insert(copy);
        }
    }

    // ---------- 自动排程引擎（auto 任务前置驱动） ----------

    /**
     * 自动排程：沿 pred→succ 拓扑序传播，自动任务 start = max(各前置推导, 开始约束)，
     * finish = start + 工期 - 1（里程碑 0 工期 start=finish）。推导公式（自然日）：
     * FS: predFinish+lag+1；SS: predStart+lag；FF: predFinish+lag-(工期-1)；SF: predStart+lag-(工期-1)。
     * 手动任务日期永不被改写；摘要任务不作为排程目标（作为前置时取子孙汇总日期）。
     * 无前置且无约束的自动任务保持当前开始日期为锚点；未排期保持未排期。
     * 依赖环已被创建时 DFS 拦截，Kahn 单遍即可收敛。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rescheduleAutoTasks(Long projectId) {
        if (!hasWbs(projectId)) {
            return;
        }
        TaskTree tree = buildTree(projectId);
        List<TaskDependency> deps = dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getProjectId, projectId));
        Map<Long, Block> byId = new HashMap<>();
        Map<Long, List<Block>> children = new HashMap<>();
        Map<Long, LocalDate> starts = new HashMap<>();
        Map<Long, LocalDate> ends = new HashMap<>();
        for (Block card : tree.ordered()) {
            byId.put(card.getId(), card);
            if (tree.depth().get(card.getId()) > 0) {
                children.computeIfAbsent(card.getParentTaskId(), k -> new ArrayList<>()).add(card);
            }
            Map<String, Object> props = properties(card);
            LocalDate start = parseDate(asString(props.get(PROP_START_DATE)));
            LocalDate end = parseDate(asString(props.get(PROP_DUE_DATE)));
            if (start == null) {
                start = end;
            }
            if (end == null) {
                end = start;
            }
            starts.put(card.getId(), start);
            ends.put(card.getId(), end);
        }

        // Kahn 拓扑序（仅统计项目内存活任务间的边）
        Map<Long, List<Long>> succEdges = new HashMap<>();
        Map<Long, List<TaskDependency>> predsBySucc = new HashMap<>();
        Map<Long, Integer> indegree = new HashMap<>();
        for (Block card : tree.ordered()) {
            indegree.put(card.getId(), 0);
        }
        for (TaskDependency dep : deps) {
            if (!indegree.containsKey(dep.getPredecessorId()) || !indegree.containsKey(dep.getSuccessorId())) {
                continue;
            }
            succEdges.computeIfAbsent(dep.getPredecessorId(), k -> new ArrayList<>()).add(dep.getSuccessorId());
            predsBySucc.computeIfAbsent(dep.getSuccessorId(), k -> new ArrayList<>()).add(dep);
            indegree.merge(dep.getSuccessorId(), 1, Integer::sum);
        }
        Deque<Long> queue = new ArrayDeque<>();
        indegree.forEach((id, deg) -> {
            if (deg == 0) {
                queue.add(id);
            }
        });
        List<Long> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            order.add(id);
            for (Long next : succEdges.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }

        for (Long id : order) {
            Block card = byId.get(id);
            if (!MODE_AUTO.equals(card.getScheduleMode())
                    || tree.childCount().getOrDefault(id, 0) > 0) {
                continue;
            }
            boolean milestone = Boolean.TRUE.equals(card.getMilestone());
            int duration = milestone ? 0 : effectiveDuration(card, starts.get(id), ends.get(id));
            LocalDate earliest = null;
            for (TaskDependency dep : predsBySucc.getOrDefault(id, List.of())) {
                // 前置有效日期：摘要懒汇总（读内存最新值，含本轮已写回的子孙日期）
                LocalDate predStart = effectiveStart(dep.getPredecessorId(), starts, children);
                LocalDate predEnd = effectiveEnd(dep.getPredecessorId(), ends, children);
                if (predStart == null || predEnd == null) {
                    continue;
                }
                int lag = dep.getLag() == null ? 0 : dep.getLag();
                LocalDate candidate = switch (dep.getType() == null ? "FS" : dep.getType()) {
                    case "SS" -> predStart.plusDays(lag);
                    case "FF" -> predEnd.plusDays(lag - (duration - 1));
                    case "SF" -> predStart.plusDays(lag - (duration - 1));
                    default -> predEnd.plusDays(lag + 1);
                };
                if (earliest == null || candidate.isAfter(earliest)) {
                    earliest = candidate;
                }
            }
            LocalDate constraint = card.getStartConstraint();
            if (constraint != null && (earliest == null || constraint.isAfter(earliest))) {
                earliest = constraint;
            }
            if (earliest == null) {
                // 无前置无约束：当前开始日期为锚点；未排期则跳过
                earliest = starts.get(id);
                if (earliest == null) {
                    continue;
                }
            }
            LocalDate newEnd = earliest.plusDays(Math.max(duration - 1, 0));
            if (earliest.equals(starts.get(id)) && newEnd.equals(ends.get(id))) {
                continue;
            }
            starts.put(id, earliest);
            ends.put(id, newEnd);
            Map<String, Object> fields = new HashMap<>(card.getFields() != null ? card.getFields() : Map.of());
            Map<String, Object> props = new HashMap<>(properties(card));
            props.put(PROP_START_DATE, earliest.toString());
            props.put(PROP_DUE_DATE, newEnd.toString());
            fields.put("properties", props);
            card.setFields(fields);
            blockMapper.updateById(card);
        }
    }

    /** 工期来源：显式 durationDays 优先，否则起止 diff+1，未排期默认 1 */
    private int effectiveDuration(Block card, LocalDate start, LocalDate end) {
        if (card.getDurationDays() != null && card.getDurationDays() > 0) {
            return card.getDurationDays();
        }
        if (start != null && end != null) {
            return (int) ChronoUnit.DAYS.between(start, end) + 1;
        }
        return 1;
    }

    /** 有效开始日期：自身与子孙 min（对齐导出/前端摘要汇总语义） */
    private LocalDate effectiveStart(Long id, Map<Long, LocalDate> starts, Map<Long, List<Block>> children) {
        LocalDate result = starts.get(id);
        for (Block kid : children.getOrDefault(id, List.of())) {
            LocalDate kidStart = effectiveStart(kid.getId(), starts, children);
            if (kidStart != null && (result == null || kidStart.isBefore(result))) {
                result = kidStart;
            }
        }
        return result;
    }

    /** 有效截止日期：自身与子孙 max */
    private LocalDate effectiveEnd(Long id, Map<Long, LocalDate> ends, Map<Long, List<Block>> children) {
        LocalDate result = ends.get(id);
        for (Block kid : children.getOrDefault(id, List.of())) {
            LocalDate kidEnd = effectiveEnd(kid.getId(), ends, children);
            if (kidEnd != null && (result == null || kidEnd.isAfter(result))) {
                result = kidEnd;
            }
        }
        return result;
    }

    // ---------- MS Project 导出 ----------

    /**
     * 导出 MSPDI(.xml)：OutlineLevel / WBS / Summary / Milestone / 四类型链接(FS/SS/FF/SF)+lag /
     * 负责人资源分配 / 进度（done=100、in_progress=50、其余 0）。
     * 日期按 MS Project 默认工作日语义：start 08:00 / finish 17:00，工期含首日；
     * 里程碑 0 工期（start=finish），摘要任务日期按子孙 min/max 汇总。
     * MS Project 可直接双击打开并另存为 .mpp。
     */
    public byte[] exportMspdi(Long userId, Long projectId) {
        Project project = checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        requireWbs(projectId);
        TaskTree tree = buildTree(projectId);
        List<TaskDependency> deps = dependencyMapper.selectList(new LambdaQueryWrapper<TaskDependency>()
                .eq(TaskDependency::getProjectId, projectId));
        String memberPropId = firstMemberPropId(project.getCardProperties());
        Map<Long, String> nicknames = assigneeNicknames(tree.ordered(), memberPropId);

        ProjectFile file = new ProjectFile();
        file.getProjectProperties().setProjectTitle(project.getName());
        // MS Project 默认工作日历：08:00-17:00、8 小时/天、40 小时/周、20 天/月
        file.getProjectProperties().setDefaultStartTime(LocalTime.of(8, 0));
        file.getProjectProperties().setDefaultEndTime(LocalTime.of(17, 0));
        file.getProjectProperties().setMinutesPerDay(480);
        // 7 天工作周：与下方自然日日历一致（7×8h），保证 MS Project 周单位换算不跳周末
        file.getProjectProperties().setMinutesPerWeek(3360);
        file.getProjectProperties().setDaysPerMonth(20);
        // 默认日历改为周一~周日全工作（8h/天）：使 MS Project 的「d」等价于本应用自然日，消除周末飘移；
        // 同时手动计划任务的资源分配需经 ManuallyScheduledTaskCalendar 包装，缺省日历会 NPE
        file.setDefaultCalendar(sevenDayCalendar(file));

        // 有效日期汇总：DFS 逆序（子先父后）逐层取自身与子孙 min/max，对齐前端摘要条推导
        Map<Long, List<Block>> children = new HashMap<>();
        for (Block card : tree.ordered()) {
            if (tree.depth().get(card.getId()) > 0) {
                children.computeIfAbsent(card.getParentTaskId(), k -> new ArrayList<>()).add(card);
            }
        }
        Map<Long, LocalDate> effStart = new HashMap<>();
        Map<Long, LocalDate> effEnd = new HashMap<>();
        for (int i = tree.ordered().size() - 1; i >= 0; i--) {
            Block card = tree.ordered().get(i);
            Map<String, Object> props = properties(card);
            LocalDate start = parseDate(asString(props.get(PROP_START_DATE)));
            LocalDate end = parseDate(asString(props.get(PROP_DUE_DATE)));
            if (start == null) {
                start = end;
            }
            if (end == null) {
                end = start;
            }
            for (Block kid : children.getOrDefault(card.getId(), List.of())) {
                LocalDate kidStart = effStart.get(kid.getId());
                LocalDate kidEnd = effEnd.get(kid.getId());
                if (kidStart != null && (start == null || kidStart.isBefore(start))) {
                    start = kidStart;
                }
                if (kidEnd != null && (end == null || kidEnd.isAfter(end))) {
                    end = kidEnd;
                }
            }
            effStart.put(card.getId(), start);
            effEnd.put(card.getId(), end);
        }

        Map<Long, Task> taskByDbId = new HashMap<>();
        Map<Long, Resource> resourceByUserId = new HashMap<>();
        int assignmentSeq = 0;
        for (Block card : tree.ordered()) {
            // 按真实父子结构添加任务：根任务挂项目、子任务挂父任务，
            // MPXJ 据此自动推导 OutlineLevel / OutlineNumber / Summary，UniqueID 由其序列统一管理
            int level = tree.depth().get(card.getId());
            Task parentTask = level == 0 ? null : taskByDbId.get(card.getParentTaskId());
            Task task = parentTask == null ? file.addTask() : parentTask.addTask();
            task.setName(card.getTitle());
            task.setWBS(tree.wbs().get(card.getId()));
            Map<String, Object> props = properties(card);
            LocalDate start = effStart.get(card.getId());
            LocalDate end = effEnd.get(card.getId());
            // 里程碑读显式标记（摘要任务不受标记影响）
            boolean milestone = Boolean.TRUE.equals(card.getMilestone())
                    && tree.childCount().getOrDefault(card.getId(), 0) == 0;
            boolean isSummary = tree.childCount().getOrDefault(card.getId(), 0) > 0;
            // 计划模式：MS Project TaskMode（默认 manual，存量任务导出为手动计划）
            task.setTaskMode(MODE_AUTO.equals(card.getScheduleMode())
                    ? TaskMode.AUTO_SCHEDULED : TaskMode.MANUALLY_SCHEDULED);
            if (start != null) {
                task.setStart(start.atTime(8, 0));
                if (milestone) {
                    // MS Project 里程碑惯例：0 工期、start=finish
                    task.setFinish(start.atTime(8, 0));
                    task.setDuration(Duration.getInstance(0, TimeUnit.DAYS));
                } else {
                    task.setFinish(end.atTime(17, 0));
                    // 工期含首日（自然日）：叶子任务优先显式工期，摘要按汇总 diff+1
                    long days = !isSummary && card.getDurationDays() != null && card.getDurationDays() > 0
                            ? card.getDurationDays()
                            : ChronoUnit.DAYS.between(start, end) + 1;
                    task.setDuration(Duration.getInstance(days, TimeUnit.DAYS));
                }
                task.setMilestone(milestone);
            }
            // 开始约束（不得早于）：仅叶子任务写入，对齐 MS Project SNET 约束
            if (!isSummary && card.getStartConstraint() != null) {
                task.setConstraintType(ConstraintType.START_NO_EARLIER_THAN);
                task.setConstraintDate(card.getStartConstraint().atTime(8, 0));
            }
            // 无日期任务保持未排期（start/finish 为空），MS Project 中显示为未排期任务
            String status = asString(props.get("status"));
            task.setPercentageComplete("done".equals(status) ? 100 : "in_progress".equals(status) ? 50 : 0);
            taskByDbId.put(card.getId(), task);
            Object assignee = memberPropId == null ? null : props.get(memberPropId);
            if (assignee != null) {
                Long assigneeId = Long.valueOf(assignee.toString());
                Resource resource = resourceByUserId.get(assigneeId);
                if (resource == null) {
                    resource = file.addResource();
                    resource.setUniqueID(resourceByUserId.size() + 1);
                    resource.setName(nicknames.getOrDefault(assigneeId, String.valueOf(assigneeId)));
                    resourceByUserId.put(assigneeId, resource);
                }
                ResourceAssignment assignment = new ResourceAssignment(file, task);
                assignment.setUniqueID(++assignmentSeq);
                assignment.setResourceUniqueID(resource.getUniqueID());
                file.getResourceAssignments().add(assignment);
            }
        }
        for (TaskDependency dep : deps) {
            Task successor = taskByDbId.get(dep.getSuccessorId());
            Task predecessor = taskByDbId.get(dep.getPredecessorId());
            if (successor != null && predecessor != null) {
                successor.addPredecessor(new Relation.Builder()
                        .predecessorTask(predecessor)
                        .successorTask(successor)
                        .type(RELATION_TYPES.getOrDefault(dep.getType(), RelationType.FINISH_START))
                        .lag(Duration.getInstance(dep.getLag() == null ? 0 : dep.getLag(), TimeUnit.DAYS)));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new MSPDIWriter().write(file, out);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "gantt.exportFailed");
        }
        return out.toByteArray();
    }

    /**
     * 7 天工作日历（周一~周日，每日 08:00-12:00 / 13:00-17:00 共 8h）。
     * 本应用工期为自然日（含首日），MS Project 默认 Standard 日历跳周末会导致导入后日期飘移；
     * 将默认日历设为全周工作后，「d」即自然日，N d 正好覆盖 N 个自然日且保留 08:00/17:00 惯例。
     */
    private ProjectCalendar sevenDayCalendar(ProjectFile file) {
        ProjectCalendar calendar = file.addCalendar();
        calendar.setName("ProTask Natural Days");
        for (DayOfWeek day : DayOfWeek.values()) {
            calendar.setWorkingDay(day, true);
            ProjectCalendarHours hours = calendar.addCalendarHours(day);
            hours.add(ProjectCalendar.DEFAULT_WORKING_MORNING);
            hours.add(ProjectCalendar.DEFAULT_WORKING_AFTERNOON);
        }
        return calendar;
    }

    /** 模板中第一个 member 类型属性 id（负责人资源分配来源） */
    private String firstMemberPropId(String cardPropertiesJson) {
        if (cardPropertiesJson == null) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(cardPropertiesJson);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    if ("member".equals(node.path("type").asText(null))) {
                        return node.path("id").asText(null);
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // 非法 JSON 视为无负责人属性
        }
        return null;
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
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
    }

    private Project checkProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        if (project == null || project.getDeleteAt() != null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.notFound");
        }
        return project;
    }

    private DependencyVO toVO(TaskDependency dep) {
        DependencyVO vo = new DependencyVO();
        vo.setId(dep.getId());
        vo.setProjectId(dep.getProjectId());
        vo.setPredecessorId(dep.getPredecessorId());
        vo.setSuccessorId(dep.getSuccessorId());
        vo.setType(dep.getType());
        vo.setLag(dep.getLag());
        return vo;
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

    /** 解析日期（仅取前 10 位 YYYY-MM-DD，兼容存量带时间的值） */
    private LocalDate parseDate(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

}