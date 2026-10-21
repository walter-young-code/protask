package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.MessageUtil;
import com.protask.common.ResultCode;
import com.protask.common.SqlUtil;
import com.protask.dto.CardPropertiesRequest;
import com.protask.dto.CalendarSubscriptionVO;
import com.protask.dto.PageResult;
import com.protask.dto.ProjectCleanupVO;
import com.protask.dto.ProjectCopyRequest;
import com.protask.dto.ProjectMemberRequest;
import com.protask.dto.ProjectMemberVO;
import com.protask.dto.ProjectRequest;
import com.protask.dto.ProjectTemplateRequest;
import com.protask.dto.ProjectTemplateUpdateRequest;
import com.protask.dto.ProjectTemplateVO;
import com.protask.dto.ProjectToTemplateRequest;
import com.protask.dto.ProjectVO;
import com.protask.dto.ShareVO;
import com.protask.dto.UserVO;
import com.protask.entity.Block;
import com.protask.entity.Notification;
import com.protask.entity.Project;
import com.protask.entity.ProjectCategory;
import com.protask.entity.ProjectCategoryAssignment;
import com.protask.entity.ProjectMember;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.NotificationMapper;
import com.protask.mapper.ProjectCategoryMapper;
import com.protask.mapper.ProjectCategoryAssignmentMapper;
import com.protask.mapper.ProjectMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import com.protask.permission.ProjectRole;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 项目服务：项目 CRUD + 成员管理 + 细粒度权限校验。
 * 权限设计对齐 Focalboard：type 变更需 MANAGE_PROJECT_TYPE，
 * 成员管理需 MANAGE_PROJECT_ROLES，删除需 DELETE_PROJECT，其余变更需 admin。
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    /** 新建项目的默认卡片动态属性模板：空，全部属性由用户自行定义 */
    private static final String DEFAULT_CARD_PROPERTIES = "[]";

    /** 需要携带选项的属性类型 */
    private static final Set<String> OPTIONED_TYPES = Set.of("select", "multiSelect");

    /** 管理后台分页单页最大条数 */
    private static final long MAX_PAGE_SIZE = 100;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectCategoryMapper projectCategoryMapper;
    private final ProjectCategoryAssignmentMapper assignmentMapper;
    private final BlockMapper blockMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;
    private final BlockService blockService;
    private final AttachmentService attachmentService;
    private final NotificationMapper notificationMapper;

    /**
     * 查询当前用户参与的所有项目（生效角色取成员角色，负责人等同 admin）。
     */
    public List<ProjectVO> listMyProjects(Long userId) {
        return projectMapper.selectMyProjects(userId);
    }

    /**
     * 创建项目，创建者自动成为 admin 成员，默认私有且卡片属性模板为空。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO createProject(Long userId, ProjectRequest request) {
        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setType(request.getType() != null ? request.getType() : "P");
        project.setIcon(request.getIcon());
        project.setCardProperties(DEFAULT_CARD_PROPERTIES);
        project.setOwnerId(userId);
        project.setCreatedBy(userId);
        project.setModifiedBy(userId);
        project.setStatus("active");
        projectMapper.insert(project);

        writeAssignment(userId, project.getId(), resolveCategoryId(userId, request.getCategoryId()));

        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(userId);
        member.setRole(ProjectRole.ADMIN.getValue());
        projectMemberMapper.insert(member);

        return getProject(userId, project.getId());
    }

    /**
     * 查询模板库：全部未删除的模板项目及其示例卡片（登录用户即可读取，无需项目权限）。
     */
    public List<ProjectTemplateVO> listTemplates() {
        List<Project> templates = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getIsTemplate, true)
                .isNull(Project::getDeleteAt)
                .orderByAsc(Project::getId));
        Map<Long, List<Block>> cardsByTemplate = loadTemplateCards(templates);
        return templates.stream()
                .map(t -> toTemplateVO(t, cardsByTemplate.getOrDefault(t.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /**
     * 管理后台分页查询模板（名称模糊匹配，仅系统管理员）。
     */
    public PageResult<ProjectTemplateVO> pageTemplates(Long operatorId, String keyword, Long page, Long size) {
        requireAdmin(operatorId);
        LambdaQueryWrapper<Project> wrapper = new LambdaQueryWrapper<Project>()
                .eq(Project::getIsTemplate, true)
                .isNull(Project::getDeleteAt);
        if (StringUtils.hasText(keyword)) {
            // 转义 LIKE 通配符，避免用户输入 %/_ 扩大匹配范围
            wrapper.like(Project::getName, SqlUtil.escapeLike(keyword.trim()));
        }
        wrapper.orderByDesc(Project::getId);
        long pageNo = page == null || page < 1 ? 1 : page;
        long pageSize = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Page<Project> result = projectMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        Map<Long, List<Block>> cardsByTemplate = loadTemplateCards(result.getRecords());
        return PageResult.of(result,
                t -> toTemplateVO(t, cardsByTemplate.getOrDefault(t.getId(), List.of())));
    }

    /**
     * 管理后台分页查询归档项目（名称模糊匹配，含负责人昵称，仅系统管理员）。
     */
    public PageResult<ProjectVO> pageArchivedProjects(Long operatorId, String keyword, Long page, Long size) {
        requireAdmin(operatorId);
        String kw = StringUtils.hasText(keyword) ? SqlUtil.escapeLike(keyword.trim()) : null;
        long pageNo = page == null || page < 1 ? 1 : page;
        long pageSize = size == null || size < 1 ? 10 : Math.min(size, MAX_PAGE_SIZE);
        Page<ProjectVO> pageParam = new Page<>(pageNo, pageSize);
        List<ProjectVO> records = projectMapper.selectArchivedProjects(pageParam, kw);
        return new PageResult<>(pageParam.getTotal(), records);
    }

    /**
     * 管理后台更新模板（名称/描述/图标，仅系统管理员）。
     */
    public ProjectTemplateVO updateTemplate(Long operatorId, Long templateId,
                                           ProjectTemplateUpdateRequest request) {
        requireAdmin(operatorId);
        Project template = requireTemplate(templateId);
        template.setName(request.getName().trim());
        template.setDescription(request.getDescription());
        template.setIcon(request.getIcon());
        template.setModifiedBy(operatorId);
        projectMapper.updateById(template);
        return toTemplateVO(template);
    }

    /**
     * 管理后台删除模板（软删除并级联软删除示例块，仅系统管理员）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long operatorId, Long templateId) {
        requireAdmin(operatorId);
        Project template = requireTemplate(templateId);
        blockService.softDeleteByProject(templateId, operatorId);
        template.setDeleteAt(LocalDateTime.now());
        template.setModifiedBy(operatorId);
        projectMapper.updateById(template);
    }

    /**
     * 从模板创建项目：复制模板的基本信息、卡片属性模板与全部块（含示例任务/视图），
     * 创建者自动成为 admin 成员，项目为普通项目（非模板）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO createProjectFromTemplate(Long userId, ProjectTemplateRequest request) {
        Project template = requireTemplate(request.getTemplateId());
        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription() != null
                ? request.getDescription() : template.getDescription());
        project.setType(request.getType() != null ? request.getType() : "P");
        project.setIcon(request.getIcon() != null ? request.getIcon() : template.getIcon());
        project.setCardProperties(template.getCardProperties() != null
                ? template.getCardProperties() : DEFAULT_CARD_PROPERTIES);
        project.setProperties(template.getProperties());
        project.setOwnerId(userId);
        project.setCreatedBy(userId);
        project.setModifiedBy(userId);
        project.setStatus("active");
        project.setIsTemplate(false);
        projectMapper.insert(project);

        writeAssignment(userId, project.getId(), resolveCategoryId(userId, request.getCategoryId()));

        ProjectMember member = new ProjectMember();
        member.setProjectId(project.getId());
        member.setUserId(userId);
        member.setRole(ProjectRole.ADMIN.getValue());
        projectMemberMapper.insert(member);

        blockService.copyBlocksFromProject(request.getTemplateId(), project.getId(), userId);
        return getProject(userId, project.getId());
    }

    /**
     * 将现有项目转为模板：复制项目结构（属性模板 + 全部块）到模板库，
     * 原项目不受影响。需项目 admin 权限；模板副本公开只读、不产生成员记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectTemplateVO convertProjectToTemplate(Long userId, Long projectId,
                                                      ProjectToTemplateRequest request) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        Project template = new Project();
        template.setName(request != null && request.getName() != null && !request.getName().isBlank()
                ? request.getName() : project.getName());
        template.setDescription(request != null && request.getDescription() != null
                ? request.getDescription() : project.getDescription());
        template.setType("O");
        template.setIcon(project.getIcon());
        template.setCardProperties(project.getCardProperties() != null
                ? project.getCardProperties() : DEFAULT_CARD_PROPERTIES);
        template.setProperties(project.getProperties());
        template.setIsTemplate(true);
        template.setOwnerId(project.getOwnerId());
        template.setCreatedBy(userId);
        template.setModifiedBy(userId);
        template.setStatus("active");
        projectMapper.insert(template);
        blockService.copyBlocksFromProject(projectId, template.getId(), userId);
        return toTemplateVO(template);
    }

    /**
     * 复制现有项目：克隆基本信息、卡片属性模板与全部块（任务/视图/评论）到新项目，
     * 仅复制者成为新项目负责人与 admin 成员，原成员不复制；
     * 项目默认视图在新副本中重映射到新视图 id。需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO copyProject(Long userId, Long projectId, ProjectCopyRequest request) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        Project copy = new Project();
        copy.setName(resolveCopyName(request != null ? request.getName() : null, project.getName()));
        copy.setDescription(request != null && request.getDescription() != null
                ? request.getDescription() : project.getDescription());
        copy.setType(request != null && request.getType() != null ? request.getType() : project.getType());
        copy.setIcon(project.getIcon());
        copy.setCardProperties(project.getCardProperties() != null
                ? project.getCardProperties() : DEFAULT_CARD_PROPERTIES);
        copy.setProperties(project.getProperties());
        copy.setOwnerId(userId);
        copy.setCreatedBy(userId);
        copy.setModifiedBy(userId);
        copy.setStatus("active");
        copy.setIsTemplate(false);
        projectMapper.insert(copy);

        // 副本沿用复制者视角的归类（原项目在复制者侧边栏所属分类）
        Long assignedCategoryId = findAssignedCategoryId(userId, projectId);
        if (assignedCategoryId != null) {
            writeAssignment(userId, copy.getId(), assignedCategoryId);
        }

        ProjectMember member = new ProjectMember();
        member.setProjectId(copy.getId());
        member.setUserId(userId);
        member.setRole(ProjectRole.ADMIN.getValue());
        projectMemberMapper.insert(member);

        Map<Long, Long> idMapping = blockService.copyBlocksFromProject(projectId, copy.getId(), userId);
        remapDefaultView(copy, idMapping, userId);
        return getProject(userId, copy.getId());
    }

    /**
     * 查询项目详情（需查看权限，公开项目非成员凭分享链接可只读访问）。
     */
    public ProjectVO getProject(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        // 归档项目仅系统管理员可见（管理后台恢复/删除），其余用户视为不存在
        if ("archived".equals(project.getStatus()) && !permissionService.isSystemAdmin(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.notFound");
        }
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        ProjectVO vo = new ProjectVO();
        BeanUtils.copyProperties(project, vo);
        fillCategory(userId, projectId, vo);
        ProjectRole role = permissionService.getEffectiveRole(userId, projectId);
        if (role == ProjectRole.NONE && permissionService.isShareVisitor(projectId)) {
            // 分享访客：非成员仅凭分享链接只读访问，展示为只读成员
            role = ProjectRole.VIEWER;
        }
        vo.setMyRole(role.getValue());
        if (project.getOwnerId() != null) {
            vo.setOwnerName(getNickname(project.getOwnerId()));
        }
        return vo;
    }

    /**
     * 开启项目分享：仅公开项目可分享，生成 UUID 分享凭证（重复开启复用现有凭证）。
     * 需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public ShareVO createShare(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        if (!"O".equals(project.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project.shareNotAvailable");
        }
        if (project.getShareId() == null) {
            project.setShareId(UUID.randomUUID().toString());
            project.setModifiedBy(userId);
            projectMapper.updateById(project);
        }
        return toShareVO(project);
    }

    /**
     * 关闭项目分享：清空分享凭证，旧链接立即失效。需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public ShareVO disableShare(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        if (project.getShareId() != null) {
            project.setShareId(null);
            project.setModifiedBy(userId);
            projectMapper.updateById(project);
        }
        return toShareVO(project);
    }

    /** 查询项目当前分享状态（shareId 为 null 表示未开启）。需项目 admin 权限。 */
    public ShareVO getShare(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        return toShareVO(project);
    }

    /**
     * 通过分享链接解析项目：shareId 本身即访问凭证，分享访客据此跳转项目详情。
     * 链接无效/已关闭/项目已删除时返回 404。
     */
    public ProjectVO resolveShare(Long userId, String shareId) {
        Project project = projectMapper.selectOne(new LambdaQueryWrapper<Project>()
                .eq(Project::getShareId, shareId));
        // 归档项目已关闭分享，链接一律失效
        if (project == null || project.getDeleteAt() != null || Boolean.TRUE.equals(project.getIsTemplate())
                || "archived".equals(project.getStatus())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.shareNotFound");
        }
        ProjectVO vo = new ProjectVO();
        BeanUtils.copyProperties(project, vo);
        fillCategory(userId, project.getId(), vo);
        // 成员按实际角色展示；非成员分享访客展示为只读成员
        ProjectRole role = permissionService.getEffectiveRole(userId, project.getId());
        if (role == ProjectRole.NONE) {
            role = ProjectRole.VIEWER;
        }
        vo.setMyRole(role.getValue());
        if (project.getOwnerId() != null) {
            vo.setOwnerName(getNickname(project.getOwnerId()));
        }
        return vo;
    }

    /** 组装分享视图：shareUrl 基于请求 Origin 拼接，缺失时回退相对路径 */
    private ShareVO toShareVO(Project project) {
        ShareVO vo = new ShareVO();
        vo.setShareId(project.getShareId());
        if (project.getShareId() != null) {
            String origin = null;
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                origin = attrs.getRequest().getHeader("Origin");
            }
            vo.setShareUrl((origin != null ? origin : "") + "/share/" + project.getShareId());
        }
        return vo;
    }

    /**
     * 开启日历订阅：生成 UUID 订阅凭证（重复开启复用现有凭证）。
     * 凭证内嵌于订阅地址，Outlook 等客户端凭此匿名拉取 ICS。需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public CalendarSubscriptionVO enableCalendarSubscription(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        if (project.getCalendarToken() == null) {
            project.setCalendarToken(UUID.randomUUID().toString());
            project.setModifiedBy(userId);
            projectMapper.updateById(project);
        }
        return toCalendarSubscriptionVO(project);
    }

    /**
     * 重新生成日历订阅凭证：旧订阅地址立即失效（凭证泄露后止损用）。需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public CalendarSubscriptionVO regenerateCalendarSubscription(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        project.setCalendarToken(UUID.randomUUID().toString());
        project.setModifiedBy(userId);
        projectMapper.updateById(project);
        return toCalendarSubscriptionVO(project);
    }

    /**
     * 关闭日历订阅：清空订阅凭证，旧地址立即失效。需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public CalendarSubscriptionVO disableCalendarSubscription(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        if (project.getCalendarToken() != null) {
            project.setCalendarToken(null);
            project.setModifiedBy(userId);
            projectMapper.updateById(project);
        }
        return toCalendarSubscriptionVO(project);
    }

    /** 查询项目当前日历订阅状态（calendarToken 为 null 表示未开启）。需项目 admin 权限。 */
    public CalendarSubscriptionVO getCalendarSubscription(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        return toCalendarSubscriptionVO(project);
    }

    /**
     * 组装订阅视图：subscribeUrl 优先基于请求 Origin 拼接（兼容反向代理与前端代理），
     * 缺失时回退请求自身 scheme + host，保证与 API 同源可达。
     */
    private CalendarSubscriptionVO toCalendarSubscriptionVO(Project project) {
        CalendarSubscriptionVO vo = new CalendarSubscriptionVO();
        vo.setCalendarToken(project.getCalendarToken());
        if (project.getCalendarToken() != null) {
            String path = "/api/v1/public/calendars/" + project.getCalendarToken() + ".ics";
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                String origin = request.getHeader("Origin");
                if (origin != null && !origin.isEmpty()) {
                    vo.setSubscribeUrl(origin + path);
                } else {
                    vo.setSubscribeUrl(ServletUriComponentsBuilder.fromRequest(request)
                            .replacePath(path).build().toUriString());
                }
            } else {
                vo.setSubscribeUrl(path);
            }
        }
        return vo;
    }

    /**
     * 更新项目信息。type 变更需 MANAGE_PROJECT_TYPE，其余仅 admin。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO updateProject(Long userId, Long projectId, ProjectRequest request) {
        Project project = getExistingProject(projectId);
        boolean typeChange = request.getType() != null;
        if (typeChange) {
            permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_TYPE);
        }
        checkAdmin(userId, projectId);
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        if (request.getType() != null) {
            project.setType(request.getType());
            // 转为私有项目时自动关闭分享，防止旧链接继续访问私有项目
            if ("P".equals(request.getType())) {
                project.setShareId(null);
            }
        }
        if (request.getIcon() != null) {
            project.setIcon(request.getIcon());
        }
        project.setModifiedBy(userId);
        projectMapper.updateById(project);
        return getProject(userId, projectId);
    }

    /**
     * 设置当前用户的项目归类（归类只影响本人侧边栏，因此无需项目 admin）。
     * categoryId 为 null/0 时清除归类。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO assignCategory(Long userId, Long projectId, Long categoryId) {
        getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        writeAssignment(userId, projectId, resolveCategoryId(userId, categoryId));
        return getProject(userId, projectId);
    }

    /**
     * 设置项目默认视图（切换项目后优先展示）：视图 id 存入 project.properties 的
     * defaultViewId 键（保留其他扩展键），viewId 为 null 时清除默认视图
     * （需卡片管理权限，与前端 editable 一致）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO setDefaultView(Long userId, Long projectId, Long viewId) {
        Project project = getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_CARDS);
        if (viewId != null) {
            Block view = blockMapper.selectById(viewId);
            if (view == null || view.getDeleteAt() != null || !projectId.equals(view.getProjectId())
                    || !BlockTypes.VIEW.equals(view.getType())) {
                throw new BusinessException(ResultCode.NOT_FOUND, "block.notFound");
            }
        }
        Map<String, Object> properties = parseProperties(project.getProperties());
        if (viewId == null) {
            properties.remove("defaultViewId");
        } else {
            properties.put("defaultViewId", viewId);
        }
        project.setProperties(writeProperties(properties));
        project.setModifiedBy(userId);
        projectMapper.updateById(project);
        return getProject(userId, projectId);
    }

    /**
     * 复制项目默认名称：原名称 + 副本后缀（超长时截断到 100）。
     */
    private String resolveCopyName(String requestedName, String sourceName) {
        String name = requestedName != null && !requestedName.isBlank()
                ? requestedName : sourceName + MessageUtil.get("project.copySuffix");
        return name.length() > 100 ? name.substring(0, 100) : name;
    }

    /**
     * 重映射副本的默认视图：旧视图 id 在副本中变为新 id，找不到时清除默认视图，
     * 避免新项目指向旧项目的视图块。
     */
    private void remapDefaultView(Project copy, Map<Long, Long> idMapping, Long userId) {
        Map<String, Object> properties = parseProperties(copy.getProperties());
        Object defaultViewId = properties.get("defaultViewId");
        if (!(defaultViewId instanceof Number)) {
            return;
        }
        Long newViewId = idMapping.get(((Number) defaultViewId).longValue());
        if (newViewId != null) {
            properties.put("defaultViewId", newViewId);
        } else {
            properties.remove("defaultViewId");
        }
        copy.setProperties(writeProperties(properties));
        copy.setModifiedBy(userId);
        projectMapper.updateById(copy);
    }

    /** 解析项目扩展属性 JSON（null/非法返回空 Map） */
    private Map<String, Object> parseProperties(String properties) {
        if (properties == null || properties.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(properties, new TypeReference<>() {
            });
            return map != null ? map : new HashMap<>();
        } catch (JsonProcessingException e) {
            return new HashMap<>();
        }
    }

    /** 序列化项目扩展属性 JSON */
    private String writeProperties(Map<String, Object> properties) {
        try {
            return OBJECT_MAPPER.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "common.internalError");
        }
    }

    /**
     * 解析分类ID：null 原样；0 表示清除分类（返回 null）；>0 校验存在且属于当前用户后返回；<0 拒绝。
     */
    private Long resolveCategoryId(Long userId, Long categoryId) {
        if (categoryId == null || categoryId == 0) {
            return null;
        }
        if (categoryId < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "category.invalidId");
        }
        ProjectCategory category = projectCategoryMapper.selectById(categoryId);
        if (category == null || !category.getCreatedBy().equals(userId)) {
            // 分类不存在或属于其他用户时同样返回 404，避免泄露他人分类存在性
            throw new BusinessException(ResultCode.NOT_FOUND, "category.notFound");
        }
        return categoryId;
    }

    /** 填充当前用户视角的项目归类（无归类记录时保持未分类） */
    private void fillCategory(Long userId, Long projectId, ProjectVO vo) {
        Long categoryId = findAssignedCategoryId(userId, projectId);
        if (categoryId == null) {
            return;
        }
        ProjectCategory category = projectCategoryMapper.selectById(categoryId);
        if (category != null) {
            vo.setCategoryId(category.getId());
            vo.setCategoryName(category.getName());
        }
    }

    /** 查询用户对项目的归类ID（null 表示未分类） */
    private Long findAssignedCategoryId(Long userId, Long projectId) {
        ProjectCategoryAssignment assignment = assignmentMapper.selectOne(
                new LambdaQueryWrapper<ProjectCategoryAssignment>()
                        .eq(ProjectCategoryAssignment::getUserId, userId)
                        .eq(ProjectCategoryAssignment::getProjectId, projectId));
        return assignment != null ? assignment.getCategoryId() : null;
    }

    /** 写入/更新用户对项目的归类；categoryId 为 null 时清除归类 */
    private void writeAssignment(Long userId, Long projectId, Long categoryId) {
        ProjectCategoryAssignment existing = assignmentMapper.selectOne(
                new LambdaQueryWrapper<ProjectCategoryAssignment>()
                        .eq(ProjectCategoryAssignment::getUserId, userId)
                        .eq(ProjectCategoryAssignment::getProjectId, projectId));
        if (categoryId == null) {
            if (existing != null) {
                assignmentMapper.deleteById(existing.getId());
            }
            return;
        }
        if (existing == null) {
            ProjectCategoryAssignment assignment = new ProjectCategoryAssignment();
            assignment.setUserId(userId);
            assignment.setProjectId(projectId);
            assignment.setCategoryId(categoryId);
            assignmentMapper.insert(assignment);
        } else {
            existing.setCategoryId(categoryId);
            assignmentMapper.updateById(existing);
        }
    }

    /**
     * 更新项目卡片动态属性模板：请求中的属性列表即新模板（整体替换）；
     * 被删除的属性会级联清理全部卡片上的对应值（需 MANAGE_PROJECT_PROPERTIES）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO updateCardProperties(Long userId, Long projectId, CardPropertiesRequest request) {
        getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_PROPERTIES);
        List<CardPropertiesRequest.CardPropertyDef> properties =
                request.getProperties() != null ? request.getProperties() : List.of();
        validateCustomProperties(properties);

        Project project = getExistingProject(projectId);
        List<CardPropertiesRequest.CardPropertyDef> existing = parseCardProperties(project.getCardProperties());

        // 被删除的属性 id：旧模板中存在而新模板中不再存在
        Set<String> newIds = properties.stream()
                .map(CardPropertiesRequest.CardPropertyDef::getId)
                .collect(Collectors.toSet());
        List<String> deletedIds = existing.stream()
                .map(CardPropertiesRequest.CardPropertyDef::getId)
                .filter(id -> !newIds.contains(id))
                .collect(Collectors.toList());
        if (!deletedIds.isEmpty()) {
            blockService.removePropertyFromCards(projectId, deletedIds, userId);
        }

        try {
            project.setCardProperties(OBJECT_MAPPER.writeValueAsString(properties));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "common.internalError");
        }
        project.setModifiedBy(userId);
        projectMapper.updateById(project);
        return getProject(userId, projectId);
    }

    /**
     * 软删除项目并级联软删除其全部块（需 DELETE_PROJECT 权限）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteProject(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.DELETE_PROJECT);
        blockService.softDeleteByProject(projectId, userId);
        project.setDeleteAt(LocalDateTime.now());
        project.setModifiedBy(userId);
        projectMapper.updateById(project);
    }

    /**
     * 归档项目：归档后从成员侧边栏移除，仅管理后台「归档项目」中可恢复/删除；
     * 归档时自动关闭分享，旧链接立即失效。需项目 admin 权限。
     */
    @Transactional(rollbackFor = Exception.class)
    public void archiveProject(Long userId, Long projectId) {
        Project project = getExistingProject(projectId);
        checkAdmin(userId, projectId);
        if ("archived".equals(project.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "project.alreadyArchived");
        }
        project.setStatus("archived");
        project.setShareId(null);
        project.setModifiedBy(userId);
        projectMapper.updateById(project);
    }

    /**
     * 管理后台恢复归档项目：恢复正常状态，重新出现在成员侧边栏（仅系统管理员）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectVO restoreProject(Long operatorId, Long projectId) {
        requireAdmin(operatorId);
        Project project = getExistingProject(projectId);
        if (!"archived".equals(project.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project.notArchived");
        }
        project.setStatus("active");
        project.setModifiedBy(operatorId);
        projectMapper.updateById(project);
        return getProject(operatorId, projectId);
    }

    /**
     * 管理后台删除归档项目：软删除项目并级联软删除其全部块（仅系统管理员）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteArchivedProject(Long operatorId, Long projectId) {
        requireAdmin(operatorId);
        Project project = getExistingProject(projectId);
        if (!"archived".equals(project.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project.notArchived");
        }
        blockService.softDeleteByProject(projectId, operatorId);
        project.setDeleteAt(LocalDateTime.now());
        project.setModifiedBy(operatorId);
        projectMapper.updateById(project);
    }

    /**
     * 管理后台统计指定日期前（含当天）已软删的项目及关联数据量（仅系统管理员，不执行删除）。
     * 供前端确认弹窗预览影响范围。
     */
    public ProjectCleanupVO countDeletedProjects(Long operatorId, LocalDate before) {
        requireAdmin(operatorId);
        List<Project> projects = listSoftDeletedBefore(before);
        ProjectCleanupVO vo = new ProjectCleanupVO();
        vo.setProjects(projects.size());
        for (Project project : projects) {
            List<Long> blockIds = listBlockIds(project.getId());
            vo.setBlocks(vo.getBlocks() + blockIds.size());
            vo.setAttachments(vo.getAttachments()
                    + (int) attachmentService.countByProject(project.getId()));
            vo.setAttachmentFiles(vo.getAttachmentFiles()
                    + (int) attachmentService.countFilesByProject(project.getId()));
            vo.setMembers(vo.getMembers() + projectMemberMapper.selectCount(
                    new LambdaQueryWrapper<ProjectMember>()
                            .eq(ProjectMember::getProjectId, project.getId())).intValue());
            vo.setNotifications(vo.getNotifications() + notificationMapper.selectCount(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getProjectId, project.getId())).intValue());
        }
        return vo;
    }

    /**
     * 管理后台物理清理指定日期前（含当天）已软删的项目（仅系统管理员）：
     * 级联物理删除全部块、附件（含磁盘文件）、成员关系与站内通知，项目行一并删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectCleanupVO physicallyDeleteProjects(Long operatorId, LocalDate before) {
        requireAdmin(operatorId);
        List<Project> projects = listSoftDeletedBefore(before);
        ProjectCleanupVO vo = new ProjectCleanupVO();
        vo.setProjects(projects.size());
        for (Project project : projects) {
            List<Long> blockIds = listBlockIds(project.getId());
            vo.setBlocks(vo.getBlocks() + blockIds.size());
            AttachmentService.AttachmentCleanupResult attachmentResult =
                    attachmentService.physicallyDeleteByProject(project.getId());
            vo.setAttachments(vo.getAttachments() + attachmentResult.records());
            vo.setAttachmentFiles(vo.getAttachmentFiles() + attachmentResult.files());
            vo.setMembers(vo.getMembers() + projectMemberMapper.delete(
                    new LambdaQueryWrapper<ProjectMember>()
                            .eq(ProjectMember::getProjectId, project.getId())));
            vo.setNotifications(vo.getNotifications() + notificationMapper.delete(
                    new LambdaQueryWrapper<Notification>()
                            .eq(Notification::getProjectId, project.getId())));
            if (!blockIds.isEmpty()) {
                blockMapper.deleteBatchIds(blockIds);
            }
            projectMapper.deleteById(project.getId());
        }
        return vo;
    }

    // ---------- 成员管理（均需 MANAGE_PROJECT_ROLES） ----------

    /**
     * 查询项目成员列表（需查看权限）。
     */
    public List<ProjectMemberVO> listMembers(Long userId, Long projectId) {
        getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.VIEW_PROJECT);
        // 分享访客仅授只读查看，成员列表属成员管理范畴，一律拒绝
        ProjectRole role = permissionService.getEffectiveRole(userId, projectId);
        if (role == ProjectRole.NONE && permissionService.isShareVisitor(projectId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "project.permissionDenied");
        }
        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>().eq(ProjectMember::getProjectId, projectId));
        // 批量查询成员用户信息，避免逐个成员查询（N+1）
        Map<Long, User> users = loadUsers(members.stream().map(ProjectMember::getUserId).toList());
        return members.stream().map(m -> {
            ProjectMemberVO vo = new ProjectMemberVO();
            vo.setUserId(m.getUserId());
            vo.setRole(m.getRole());
            vo.setJoinedAt(m.getJoinedAt());
            User user = users.get(m.getUserId());
            if (user != null) {
                vo.setUsername(user.getUsername());
                vo.setNickname(user.getNickname());
                vo.setAvatar(user.getAvatar());
                vo.setFullName(UserVO.fullNameOf(user.getFirstName(), user.getLastName()));
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 添加项目成员。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectMemberVO addMember(Long userId, Long projectId, ProjectMemberRequest request) {
        getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_ROLES);
        User target = userMapper.selectById(request.getUserId());
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "user.notFound");
        }
        ProjectMember existing = getMember(projectId, request.getUserId());
        if (existing != null) {
            throw new BusinessException(ResultCode.CONFLICT, "project.memberExists");
        }
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setUserId(request.getUserId());
        member.setRole(request.getRole());
        projectMemberMapper.insert(member);
        ProjectMemberVO vo = new ProjectMemberVO();
        vo.setUserId(target.getId());
        vo.setUsername(target.getUsername());
        vo.setNickname(target.getNickname());
        vo.setAvatar(target.getAvatar());
        vo.setFullName(UserVO.fullNameOf(target.getFirstName(), target.getLastName()));
        vo.setRole(member.getRole());
        vo.setJoinedAt(member.getJoinedAt());
        return vo;
    }

    /**
     * 变更项目成员角色（负责人身份不可变更）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectMemberVO updateMemberRole(Long userId, Long projectId, ProjectMemberRequest request) {
        Project project = getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_ROLES);
        if (request.getUserId().equals(project.getOwnerId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project.ownerRoleLocked");
        }
        ProjectMember member = requireMember(projectId, request.getUserId());
        member.setRole(request.getRole());
        projectMemberMapper.updateById(member);
        return listMembers(userId, projectId).stream()
                .filter(vo -> vo.getUserId().equals(request.getUserId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 移除项目成员（负责人不可移除）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long userId, Long projectId, Long targetUserId) {
        Project project = getExistingProject(projectId);
        permissionService.checkPermission(userId, projectId, ProjectPermission.MANAGE_PROJECT_ROLES);
        if (targetUserId.equals(project.getOwnerId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "project.ownerRoleLocked");
        }
        ProjectMember member = requireMember(projectId, targetUserId);
        projectMemberMapper.deleteById(member.getId());
        // 清除被移除成员在该项目的个人归类，避免重新加入后残留旧归类
        assignmentMapper.delete(new LambdaQueryWrapper<ProjectCategoryAssignment>()
                .eq(ProjectCategoryAssignment::getUserId, targetUserId)
                .eq(ProjectCategoryAssignment::getProjectId, projectId));
    }

    // ---------- 内部工具 ----------

    /** 校验属性列表：id 不重复，选项类型必须携带不重复的选项 */
    private void validateCustomProperties(List<CardPropertiesRequest.CardPropertyDef> custom) {
        Set<String> seenIds = new HashSet<>();
        for (CardPropertiesRequest.CardPropertyDef prop : custom) {
            if (!seenIds.add(prop.getId())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "project.propertyIdDuplicate");
            }
            if (!OPTIONED_TYPES.contains(prop.getType())) {
                continue;
            }
            if (prop.getOptions() == null || prop.getOptions().isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "project.propertyOptionsRequired");
            }
            Set<String> optionIds = new HashSet<>();
            for (CardPropertiesRequest.CardPropertyOption option : prop.getOptions()) {
                if (!optionIds.add(option.getId())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "project.propertyOptionDuplicate");
                }
            }
        }
    }

    /** 解析已存储的属性模板 JSON（解析失败时返回空列表） */
    private List<CardPropertiesRequest.CardPropertyDef> parseCardProperties(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<CardPropertiesRequest.CardPropertyDef> parsed = OBJECT_MAPPER.readValue(
                    json, new TypeReference<List<CardPropertiesRequest.CardPropertyDef>>() {});
            return parsed != null ? parsed : List.of();
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private Project getExistingProject(Long projectId) {
        Project project = projectMapper.selectById(projectId);
        // 模板项目仅通过模板库接口访问，不参与常规项目操作
        if (project == null || project.getDeleteAt() != null || Boolean.TRUE.equals(project.getIsTemplate())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.notFound");
        }
        return project;
    }

    /** 查询模板项目（不存在/已删除/非模板均视为未找到） */
    private Project requireTemplate(Long templateId) {
        Project template = projectMapper.selectById(templateId);
        if (template == null || template.getDeleteAt() != null
                || !Boolean.TRUE.equals(template.getIsTemplate())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "template.notFound");
        }
        return template;
    }

    /** 单模板场景：查询示例卡片后转视图 */
    private ProjectTemplateVO toTemplateVO(Project template) {
        return toTemplateVO(template, blockService.listCards(template.getId()));
    }

    /** 模板项目转模板库视图（含示例卡片） */
    private ProjectTemplateVO toTemplateVO(Project template, List<Block> sampleCards) {
        ProjectTemplateVO vo = new ProjectTemplateVO();
        vo.setId(template.getId());
        vo.setName(template.getName());
        vo.setDescription(template.getDescription());
        vo.setIcon(template.getIcon());
        vo.setCardProperties(template.getCardProperties());
        vo.setCreatedAt(template.getCreatedAt());
        vo.setSampleCards(sampleCards.stream()
                .map(this::toSampleCard)
                .collect(Collectors.toList()));
        return vo;
    }

    /** 一次性查询多个模板的示例卡片（排除已删除），按模板 ID 分组 */
    private Map<Long, List<Block>> loadTemplateCards(List<Project> templates) {
        if (templates.isEmpty()) {
            return Map.of();
        }
        List<Long> templateIds = templates.stream().map(Project::getId).toList();
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .in(Block::getProjectId, templateIds)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt)
                .orderByAsc(Block::getCreateAt));
        Map<Long, List<Block>> grouped = new HashMap<>();
        for (Block card : cards) {
            grouped.computeIfAbsent(card.getProjectId(), key -> new ArrayList<>()).add(card);
        }
        return grouped;
    }

    /** 批量查询用户（成员列表等场景避免 N+1） */
    private Map<Long, User> loadUsers(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, User> users = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            users.put(user.getId(), user);
        }
        return users;
    }

    /** card 块转模板示例卡片视图 */
    @SuppressWarnings("unchecked")
    private ProjectTemplateVO.SampleCardVO toSampleCard(Block card) {
        ProjectTemplateVO.SampleCardVO vo = new ProjectTemplateVO.SampleCardVO();
        vo.setTitle(card.getTitle());
        if (card.getFields() != null) {
            Object icon = card.getFields().get("icon");
            vo.setIcon(icon != null ? icon.toString() : null);
            Object properties = card.getFields().get("properties");
            if (properties instanceof Map) {
                vo.setProperties(new HashMap<>((Map<String, Object>) properties));
            }
        }
        return vo;
    }

    private void checkAdmin(Long userId, Long projectId) {
        // 与 PermissionService 权限矩阵一致：系统管理员对所有项目拥有管理权限
        if (permissionService.isSystemAdmin(userId)) {
            return;
        }
        ProjectRole role = permissionService.getEffectiveRole(userId, projectId);
        if (!role.isAtLeast(ProjectRole.ADMIN)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "project.permissionDenied");
        }
    }

    /** 校验操作者是否为系统管理员（管理后台模板管理） */
    private void requireAdmin(Long operatorId) {
        if (!permissionService.isSystemAdmin(operatorId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "admin.permissionDenied");
        }
    }

    /** 查询指定日期前（含当天）已软删的项目（含模板项目） */
    private List<Project> listSoftDeletedBefore(LocalDate before) {
        LocalDateTime limit = before.plusDays(1).atStartOfDay();
        return projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .isNotNull(Project::getDeleteAt)
                .lt(Project::getDeleteAt, limit));
    }

    /** 项目下全部块 id（含已软删块，物理清理时一并删除） */
    private List<Long> listBlockIds(Long projectId) {
        return blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
    }

    private ProjectMember getMember(Long projectId, Long targetUserId) {
        return projectMemberMapper.selectOne(new LambdaQueryWrapper<ProjectMember>()
                .eq(ProjectMember::getProjectId, projectId)
                .eq(ProjectMember::getUserId, targetUserId));
    }

    private ProjectMember requireMember(Long projectId, Long targetUserId) {
        ProjectMember member = getMember(projectId, targetUserId);
        if (member == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "project.memberNotFound");
        }
        return member;
    }

    private String getNickname(Long ownerId) {
        User owner = userMapper.selectById(ownerId);
        return owner != null ? owner.getNickname() : null;
    }
}
