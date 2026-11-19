package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.TaskImportResultVO;
import com.protask.service.TaskExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 任务 Excel(.xlsx) 导入导出接口：导出需查看权限（所有项目可用），
 * 导入/模板下载需卡片管理权限；导入仅新建任务，校验错误以逐行报告返回。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskExcelController {

    private static final MediaType XLSX = MediaType
            .parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final TaskExcelService taskExcelService;

    /** 导出项目任务为 .xlsx（WBS 项目含层级/里程碑/依赖列） */
    @GetMapping("/projects/{projectId}/export/tasks.xlsx")
    public ResponseEntity<byte[]> exportTasks(@PathVariable Long projectId) {
        byte[] xlsx = taskExcelService.exportExcel(StpUtil.getLoginIdAsLong(), projectId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("project-" + projectId + "-tasks.xlsx", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(XLSX)
                .body(xlsx);
    }

    /** 下载导入模板（固定表头 + 示例行） */
    @GetMapping("/projects/{projectId}/import/template.xlsx")
    public ResponseEntity<byte[]> importTemplate(@PathVariable Long projectId) {
        byte[] xlsx = taskExcelService.importTemplate(StpUtil.getLoginIdAsLong(), projectId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("project-" + projectId + "-import-template.xlsx", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(XLSX)
                .body(xlsx);
    }

    /** 导入 Excel 新建任务：errors 非空表示校验失败、未写入任何任务 */
    @PostMapping("/projects/{projectId}/import/tasks.xlsx")
    public Result<TaskImportResultVO> importTasks(@PathVariable Long projectId,
                                                  @RequestParam("file") MultipartFile file) {
        return Result.ok(taskExcelService.importExcel(StpUtil.getLoginIdAsLong(), projectId, file));
    }
}