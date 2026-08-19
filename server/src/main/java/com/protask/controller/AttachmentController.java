package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.AttachmentVO;
import com.protask.entity.Attachment;
import com.protask.service.AttachmentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * 任务附件接口：图片附件公开静态访问，非图片附件通过 /download 鉴权下载。
 * 注意：路径与静态资源 /api/v1/upload/files/** 无重叠，避免注解映射劫持静态请求。
 */
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /** 查询任务附件列表 */
    @GetMapping
    public Result<List<AttachmentVO>> list(@PathVariable Long taskId) {
        return Result.ok(attachmentService.list(StpUtil.getLoginIdAsLong(), taskId));
    }

    /** 上传附件到任务 */
    @PostMapping
    public Result<AttachmentVO> upload(@PathVariable Long taskId,
                                       @RequestParam("file") MultipartFile file) throws IOException {
        return Result.ok(attachmentService.upload(StpUtil.getLoginIdAsLong(), taskId, file));
    }

    /**
     * 鉴权下载非图片附件（需项目查看权限）：
     * 浏览器新标签页无法携带 Authorization 头，token 可通过同名请求参数传入（sa-token is-read-body）。
     * PDF 以 inline 方式返回支持浏览器原生预览，其余类型强制下载并使用原始文件名。
     */
    @GetMapping("/{id}/download")
    public void download(@PathVariable Long taskId, @PathVariable Long id,
                         HttpServletResponse response) throws IOException {
        AttachmentService.DownloadPayload payload =
                attachmentService.prepareDownload(StpUtil.getLoginIdAsLong(), taskId, id);
        Attachment attachment = payload.attachment();
        String contentType = attachment.getContentType() != null && !attachment.getContentType().isBlank()
                ? attachment.getContentType() : "application/octet-stream";
        response.setContentType(contentType);
        String disposition = "application/pdf".equals(contentType.toLowerCase(Locale.ROOT)) ? "inline" : "attachment";
        String encodedName = URLEncoder.encode(attachment.getOriginalName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setHeader("Content-Disposition",
                disposition + "; filename=\"" + encodedName + "\"; filename*=UTF-8''" + encodedName);
        Files.copy(payload.file(), response.getOutputStream());
    }

    /** 删除附件（同步移除任务描述中的 Markdown 引用） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long taskId, @PathVariable Long id) {
        attachmentService.delete(StpUtil.getLoginIdAsLong(), taskId, id);
        return Result.ok();
    }
}
