package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.CardPropertiesRequest;
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
import net.sf.mpxj.Duration;
import net.sf.mpxj.ProjectFile;
import net.sf.mpxj.Relation;
import net.sf.mpxj.RelationType;
import net.sf.mpxj.Resource;
import net.sf.mpxj.ResourceAssignment;
import net.sf.mpxj.Task;
import net.sf.mpxj.TimeUnit;
import net.sf.mpxj.mspdi.MSPDIWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 树序编号、父子防环、FS 依赖管理（DFS 环检测）与 MS Project(MSPDI) 导出。
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

    // ---------- FS 依赖管理 ----------

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

    /** 创建 FS 依赖：自依赖/重复/环（DFS）拦截 */
    @Transactional(rollbackFor = Exception.class)
    public DependencyVO createDependency(Long userId, Long projectId, DependencyRequest request) {
        checkProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_CARDS);
        requireWbs(projectId);
        String type = request.getType() == null ? "FS" : request.getType();
        if (!"FS".equals(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "dependency.typeUnsupported");
        }
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
        // 环检测：沿现有 pred→succ 边从 successor 出发 DFS，若能到达 predecessor 则成环
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
        dep.setLag(request.getLag() == null ? 0 : request.getLag());
        dep.setCreateAt(LocalDateTime.now());
        dependencyMapper.insert(dep);
        return toVO(dep);
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

    // ---------- MS Project 导出 ----------

    /**
     * 导出 MSPDI(.xml)：OutlineLevel / WBS / Summary / Milestone / FS 链接 /
     * 负责人资源分配 / 进度（done=100、in_progress=50、其余 0）。
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
            LocalDate start = parseDate(asString(props.get(PROP_START_DATE)));
            LocalDate end = parseDate(asString(props.get(PROP_DUE_DATE)));
            if (start == null) {
                start = end;
            }
            if (end == null) {
                end = start;
            }
            if (start != null) {
                task.setStart(start.atStartOfDay());
                task.setFinish(end.atStartOfDay());
                task.setDuration(Duration.getInstance(ChronoUnit.DAYS.between(start, end), TimeUnit.DAYS));
                // 里程碑读显式标记（摘要任务不受标记影响）
                task.setMilestone(Boolean.TRUE.equals(card.getMilestone())
                        && tree.childCount().getOrDefault(card.getId(), 0) == 0);
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
                        .type(RelationType.FINISH_START)
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