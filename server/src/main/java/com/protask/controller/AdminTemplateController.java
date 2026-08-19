package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.PageResult;
import com.protask.dto.ProjectTemplateUpdateRequest;
import com.protask.dto.ProjectTemplateVO;
import com.protask.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台模板接口（仅系统管理员可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/templates")
@RequiredArgsConstructor
public class AdminTemplateController {

    private final ProjectService projectService;

    /** 分页查询模板（名称模糊匹配） */
    @GetMapping
    public Result<PageResult<ProjectTemplateVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return Result.ok(projectService.pageTemplates(StpUtil.getLoginIdAsLong(), keyword, page, size));
    }

    /** 更新模板（名称/描述/图标） */
    @PutMapping("/{id}")
    public Result<ProjectTemplateVO> update(@PathVariable Long id,
                                            @Valid @RequestBody ProjectTemplateUpdateRequest request) {
        return Result.ok(projectService.updateTemplate(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 删除模板（软删除，模板库不再展示） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.deleteTemplate(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
