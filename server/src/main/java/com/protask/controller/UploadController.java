package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BusinessException;
import com.protask.common.Result;
import com.protask.common.ResultCode;
import com.protask.dto.UploadVO;
import com.protask.entity.UploadImage;
import com.protask.mapper.UploadImageMapper;
import com.protask.permission.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件上传接口（当前仅支持图片，用于任务描述 Markdown 插图）。
 */
@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    /** 允许的图片扩展名（白名单，防御恶意文件） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final UploadImageMapper uploadImageMapper;
    private final PermissionService permissionService;

    @Value("${protask.upload-dir:uploads}")
    private String uploadDir;

    /** 上传图片，返回可通过 /api/v1/upload/files/** 访问的 URL，并记录归属 */
    @PostMapping("/image")
    @Transactional(rollbackFor = Exception.class)
    public Result<UploadVO> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "upload.fileEmpty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "upload.notImage");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "upload.notImage");
        }
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(dir);
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        file.transferTo(dir.resolve(filename).toFile());

        UploadImage record = new UploadImage();
        record.setStoredName(filename);
        record.setCreatedBy(StpUtil.getLoginIdAsLong());
        try {
            uploadImageMapper.insert(record);
        } catch (RuntimeException e) {
            // 归属记录写入失败时清理已落盘的文件，避免产生无主孤儿文件
            try {
                Files.deleteIfExists(dir.resolve(filename));
            } catch (IOException ignored) {
                // 清理失败仅丢失本次文件，不阻断异常上抛
            }
            throw e;
        }
        return Result.ok(new UploadVO("/api/v1/upload/files/" + filename));
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 删除已上传的图片（需登录）。
     * 注意：路径不能与静态资源 /files/** 冲突（注解映射优先于静态资源映射，会劫持 GET 图片请求）。
     * 仅允许删除 UUID 命名格式的文件，防止路径穿越删除任意文件；
     * 仅上传本人或系统管理员可删除，无归属记录的历史文件一律拒绝。
     */
    @DeleteMapping("/delete/{filename}")
    public Result<Void> deleteImage(@PathVariable String filename) {
        // 静态资源 GET 已对登录放行，删除操作必须显式校验登录
        Long userId = StpUtil.getLoginIdAsLong();
        if (!filename.matches("[0-9a-f]{32}\\.(png|jpg|jpeg|gif|webp|bmp)")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "upload.invalidFilename");
        }
        UploadImage record = uploadImageMapper.selectOne(new LambdaQueryWrapper<UploadImage>()
                .eq(UploadImage::getStoredName, filename));
        if (record == null || (!record.getCreatedBy().equals(userId)
                && !permissionService.isSystemAdmin(userId))) {
            // 无归属记录或非本人文件统一拒绝，不泄露文件是否存在
            throw new BusinessException(ResultCode.FORBIDDEN, "upload.deleteDenied");
        }
        Path file = Paths.get(uploadDir).toAbsolutePath().normalize().resolve(filename);
        if (!Files.exists(file)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "upload.fileNotFound");
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "upload.deleteFailed");
        }
        uploadImageMapper.deleteById(record.getId());
        return Result.ok();
    }
}
