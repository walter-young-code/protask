package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.BusinessException;
import com.protask.common.Result;
import com.protask.common.ResultCode;
import com.protask.dto.PageResult;
import com.protask.dto.ProjectCleanupVO;
import com.protask.dto.ProjectVO;
import com.protask.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 管理后台归档项目接口（仅系统管理员可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/projects")
@RequiredArgsConstructor
public class AdminProjectController {

    private final ProjectService projectService;

    /** 分页查询归档项目（名称模糊匹配） */
    @GetMapping("/archived")
    public Result<PageResult<ProjectVO>> pageArchived(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return Result.ok(projectService.pageArchivedProjects(
                StpUtil.getLoginIdAsLong(), keyword, page, size));
    }

    /** 恢复归档项目（恢复正常状态，重新出现在成员侧边栏） */
    @PostMapping("/{id}/restore")
    public Result<ProjectVO> restore(@PathVariable Long id) {
        return Result.ok(projectService.restoreProject(StpUtil.getLoginIdAsLong(), id));
    }

    /** 删除归档项目（软删除，不可恢复） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.deleteArchivedProject(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    /** 统计指定日期前（含当天）已软删的项目及关联数据量（仅系统管理员，不执行删除） */
    @GetMapping("/deleted/stat")
    public Result<ProjectCleanupVO> statDeleted(@RequestParam String before) {
        return Result.ok(projectService.countDeletedProjects(
                StpUtil.getLoginIdAsLong(), parseBefore(before)));
    }

    /** 物理清理指定日期前（含当天）已软删的项目：级联删除块/附件/成员/通知（仅系统管理员） */
    @DeleteMapping("/deleted")
    public Result<ProjectCleanupVO> cleanupDeleted(@RequestParam String before) {
        return Result.ok(projectService.physicallyDeleteProjects(
                StpUtil.getLoginIdAsLong(), parseBefore(before)));
    }

    /** 解析 yyyy-MM-dd 日期参数，非法格式抛 400 */
    private LocalDate parseBefore(String before) {
        try {
            return LocalDate.parse(before);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "admin.cleanupInvalidDate");
        }
    }
}
