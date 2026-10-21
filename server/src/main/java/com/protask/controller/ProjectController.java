package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.CalendarSubscriptionVO;
import com.protask.dto.CardPropertiesRequest;
import com.protask.dto.DefaultViewRequest;
import com.protask.dto.ProjectCopyRequest;
import com.protask.dto.ProjectCategoryAssignRequest;
import com.protask.dto.ProjectMemberRequest;
import com.protask.dto.ProjectMemberVO;
import com.protask.dto.ProjectRequest;
import com.protask.dto.ProjectTemplateRequest;
import com.protask.dto.ProjectTemplateVO;
import com.protask.dto.ProjectToTemplateRequest;
import com.protask.dto.ProjectVO;
import com.protask.dto.ShareVO;
import com.protask.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目接口（含成员管理）。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    /** 查询我参与的项目列表 */
    @GetMapping
    public Result<List<ProjectVO>> listMyProjects() {
        return Result.ok(projectService.listMyProjects(StpUtil.getLoginIdAsLong()));
    }

    /** 创建项目 */
    @PostMapping
    public Result<ProjectVO> create(@Valid @RequestBody ProjectRequest request) {
        return Result.ok(projectService.createProject(StpUtil.getLoginIdAsLong(), request));
    }

    /** 从模板创建项目（复制模板的卡片属性模板与示例任务/视图） */
    @PostMapping("/from-template")
    public Result<ProjectVO> createFromTemplate(@Valid @RequestBody ProjectTemplateRequest request) {
        return Result.ok(projectService.createProjectFromTemplate(StpUtil.getLoginIdAsLong(), request));
    }

    /** 复制项目：克隆项目内容到新项目（复制者成为新项目负责人，需项目 admin 权限） */
    @PostMapping("/{id}/copy")
    public Result<ProjectVO> copy(@PathVariable Long id,
                                  @RequestBody(required = false) ProjectCopyRequest request) {
        return Result.ok(projectService.copyProject(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 将现有项目转为模板（复制项目结构到模板库，原项目不受影响） */
    @PostMapping("/{id}/to-template")
    public Result<ProjectTemplateVO> convertToTemplate(@PathVariable Long id,
                                                       @RequestBody(required = false) ProjectToTemplateRequest request) {
        return Result.ok(projectService.convertProjectToTemplate(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 查询项目详情 */
    @GetMapping("/{id}")
    public Result<ProjectVO> get(@PathVariable Long id) {
        return Result.ok(projectService.getProject(StpUtil.getLoginIdAsLong(), id));
    }

    /** 开启项目分享（仅公开项目，需项目 admin） */
    @PostMapping("/{id}/share")
    public Result<ShareVO> createShare(@PathVariable Long id) {
        return Result.ok(projectService.createShare(StpUtil.getLoginIdAsLong(), id));
    }

    /** 关闭项目分享（旧链接立即失效，需项目 admin） */
    @DeleteMapping("/{id}/share")
    public Result<ShareVO> disableShare(@PathVariable Long id) {
        return Result.ok(projectService.disableShare(StpUtil.getLoginIdAsLong(), id));
    }

    /** 查询项目当前分享状态（需项目 admin） */
    @GetMapping("/{id}/share")
    public Result<ShareVO> getShare(@PathVariable Long id) {
        return Result.ok(projectService.getShare(StpUtil.getLoginIdAsLong(), id));
    }

    /** 通过分享链接解析项目：shareId 本身即访问凭证，分享访客据此跳转项目详情 */
    @GetMapping("/share/{shareId}")
    public Result<ProjectVO> resolveShare(@PathVariable String shareId) {
        return Result.ok(projectService.resolveShare(StpUtil.getLoginIdAsLong(), shareId));
    }

    /** 开启日历订阅：生成 ICS 订阅地址（重复开启复用现有凭证，需项目 admin） */
    @PostMapping("/{id}/calendar-subscription")
    public Result<CalendarSubscriptionVO> enableCalendarSubscription(@PathVariable Long id) {
        return Result.ok(projectService.enableCalendarSubscription(StpUtil.getLoginIdAsLong(), id));
    }

    /** 重新生成日历订阅凭证：旧订阅地址立即失效（需项目 admin） */
    @PostMapping("/{id}/calendar-subscription/regenerate")
    public Result<CalendarSubscriptionVO> regenerateCalendarSubscription(@PathVariable Long id) {
        return Result.ok(projectService.regenerateCalendarSubscription(StpUtil.getLoginIdAsLong(), id));
    }

    /** 关闭日历订阅：旧订阅地址立即失效（需项目 admin） */
    @DeleteMapping("/{id}/calendar-subscription")
    public Result<CalendarSubscriptionVO> disableCalendarSubscription(@PathVariable Long id) {
        return Result.ok(projectService.disableCalendarSubscription(StpUtil.getLoginIdAsLong(), id));
    }

    /** 查询日历订阅状态（calendarToken 为 null 表示未开启，需项目 admin） */
    @GetMapping("/{id}/calendar-subscription")
    public Result<CalendarSubscriptionVO> getCalendarSubscription(@PathVariable Long id) {
        return Result.ok(projectService.getCalendarSubscription(StpUtil.getLoginIdAsLong(), id));
    }

    /** 更新项目 */
    @PutMapping("/{id}")
    public Result<ProjectVO> update(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
        return Result.ok(projectService.updateProject(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 设置项目默认视图（切换项目后优先展示，viewId 为 null 时清除） */
    @PutMapping("/{id}/default-view")
    public Result<ProjectVO> setDefaultView(@PathVariable Long id,
                                            @RequestBody DefaultViewRequest request) {
        return Result.ok(projectService.setDefaultView(StpUtil.getLoginIdAsLong(), id,
                request != null ? request.getViewId() : null));
    }

    /** 设置当前用户的项目归类（仅影响本人侧边栏，categoryId 为 null/0 表示未分类） */
    @PutMapping("/{id}/category")
    public Result<ProjectVO> assignCategory(@PathVariable Long id,
                                            @RequestBody ProjectCategoryAssignRequest request) {
        return Result.ok(projectService.assignCategory(StpUtil.getLoginIdAsLong(), id,
                request != null ? request.getCategoryId() : null));
    }

    /** 删除项目（软删除） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.deleteProject(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    /** 归档项目（归档后从侧边栏移除，仅管理后台可恢复/删除） */
    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id) {
        projectService.archiveProject(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    /** 更新项目卡片动态属性模板（自定义属性整体替换） */
    @PutMapping("/{id}/card-properties")
    public Result<ProjectVO> updateCardProperties(@PathVariable Long id,
                                                  @Valid @RequestBody CardPropertiesRequest request) {
        return Result.ok(projectService.updateCardProperties(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 查询项目成员列表 */
    @GetMapping("/{id}/members")
    public Result<List<ProjectMemberVO>> listMembers(@PathVariable Long id) {
        return Result.ok(projectService.listMembers(StpUtil.getLoginIdAsLong(), id));
    }

    /** 添加项目成员 */
    @PostMapping("/{id}/members")
    public Result<ProjectMemberVO> addMember(@PathVariable Long id,
                                             @Valid @RequestBody ProjectMemberRequest request) {
        return Result.ok(projectService.addMember(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 变更项目成员角色 */
    @PutMapping("/{id}/members")
    public Result<ProjectMemberVO> updateMemberRole(@PathVariable Long id,
                                                    @Valid @RequestBody ProjectMemberRequest request) {
        return Result.ok(projectService.updateMemberRole(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 移除项目成员 */
    @DeleteMapping("/{id}/members/{userId}")
    public Result<Void> removeMember(@PathVariable Long id, @PathVariable Long userId) {
        projectService.removeMember(StpUtil.getLoginIdAsLong(), id, userId);
        return Result.ok();
    }
}
