package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.DependencyRequest;
import com.protask.dto.DependencyVO;
import com.protask.service.GanttProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WBS 项目增强接口：FS 依赖管理与 MS Project(MSPDI) 导出。
 * 全部接口受 WBS 门禁约束（项目属性模板含内置 wbs 属性）。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GanttController {

    private final GanttProjectService ganttProjectService;

    /** 项目依赖列表（非 WBS 项目返回空列表） */
    @GetMapping("/projects/{projectId}/dependencies")
    public Result<List<DependencyVO>> listDependencies(@PathVariable Long projectId) {
        return Result.ok(ganttProjectService.listDependencies(StpUtil.getLoginIdAsLong(), projectId));
    }

    /** 创建 FS 依赖（自依赖/重复/环拦截） */
    @PostMapping("/projects/{projectId}/dependencies")
    public Result<DependencyVO> createDependency(@PathVariable Long projectId,
                                                 @Valid @RequestBody DependencyRequest request) {
        return Result.ok(ganttProjectService.createDependency(StpUtil.getLoginIdAsLong(), projectId, request));
    }

    /** 删除依赖 */
    @DeleteMapping("/dependencies/{id}")
    public Result<Void> deleteDependency(@PathVariable Long id) {
        ganttProjectService.deleteDependency(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }

    /** 导出 MS Project MSPDI(.xml)，MS Project 双击可打开并另存为 .mpp */
    @GetMapping(value = "/projects/{projectId}/export/project.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<byte[]> exportProject(@PathVariable Long projectId) {
        byte[] xml = ganttProjectService.exportMspdi(StpUtil.getLoginIdAsLong(), projectId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"project-" + projectId + ".xml\"")
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }
}