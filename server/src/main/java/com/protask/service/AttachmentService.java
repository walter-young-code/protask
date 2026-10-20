package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.AttachmentVO;
import com.protask.dto.PageResult;
import com.protask.entity.Attachment;
import com.protask.entity.Block;
import com.protask.entity.User;
import com.protask.mapper.AttachmentMapper;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.UserMapper;
import com.protask.permission.PermissionService;
import com.protask.permission.ProjectPermission;
import com.protask.storage.FileStorage;
import com.protask.storage.StorageRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 任务附件服务：图片附件存 uploads 目录（公开静态访问，供 Markdown 引用与缩略图），
 * 非图片附件存私有目录（不做静态映射，仅通过鉴权下载接口访问）；
 * 删除附件时物理删除文件并同步移除任务描述中的 Markdown 引用。
 * 读需 VIEW_PROJECT，写需 MANAGE_PROJECT_CARDS。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /** 允许的扩展名白名单：图片 + 文档/压缩包（防御恶意文件） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "md", "csv", "zip", "rar", "7z");

    /** 图片扩展名子集：需公开访问（Markdown 引用与缩略图无法携带 token） */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    /**
     * 存储相对路径格式校验（防路径穿越）：{projectId}/{taskId}/{uuid}.{ext}，
     * 兼容早期扁平文件名（仅 uuid.ext）。
     */
    private static final Pattern STORED_NAME_PATTERN = Pattern.compile(
            "(?:\\d{1,19}/\\d{1,19}/)?[0-9a-f]{32}\\.("
                    + String.join("|", ALLOWED_EXTENSIONS) + ")");

    /** 附件列表默认分页大小 */
    private static final long DEFAULT_PAGE_SIZE = 20;

    /** 附件列表单页最大条数 */
    private static final long MAX_PAGE_SIZE = 100;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AttachmentMapper attachmentMapper;
    private final BlockMapper blockMapper;
    private final UserMapper userMapper;
    private final PermissionService permissionService;
    private final StorageRegistry storageRegistry;

    /** 单任务附件数量上限（<=0 表示不限制） */
    @Value("${protask.attachment.max-per-task:20}")
    private int maxPerTask;

    /** 单项目附件数量上限（<=0 表示不限制） */
    @Value("${protask.attachment.max-per-project:0}")
    private int maxPerProject;

    /** 单用户每日上传附件数上限（<=0 表示不限制） */
    @Value("${protask.attachment.max-per-user-per-day:0}")
    private int maxPerUserPerDay;

    /**
     * 启动时迁移历史数据：早期非图片附件与图片混存在公开目录，
     * 将其移入私有目录后不再可匹名访问（迁移失败不影响启动）。
     */
    @PostConstruct
    void migrateLegacyAttachmentFiles() {
        FileStorage publicStorage = storageRegistry.publicImages();
        FileStorage privateStorage = storageRegistry.privateAttachments();
        try {
            int moved = 0;
            for (Attachment attachment : attachmentMapper.selectList(null)) {
                String storedName = attachment.getStoredName();
                // 分目录存储的新文件已在私有/公开目录就位，仅需迁移历史扁平文件
                if (storedName == null || storedName.contains("/")
                        || IMAGE_EXTENSIONS.contains(extensionOf(storedName))) {
                    continue;
                }
                if (!publicStorage.exists(storedName) || privateStorage.exists(storedName)) {
                    continue;
                }
                try (InputStream content = publicStorage.open(storedName)) {
                    privateStorage.store(storedName, content);
                }
                publicStorage.delete(storedName);
                moved++;
            }
            if (moved > 0) {
                log.info("已将 {} 个历史非图片附件迁移至私有目录", moved);
            }
        } catch (Exception e) {
            log.warn("历史附件文件迁移失败（不影响启动）", e);
        }
    }

    /**
     * 上传附件到任务（需卡片管理权限）：图片进公开目录，非图片进私有目录；
     * 文件按 {projectId}/{taskId}/ 分目录存放，落盘前校验数量配额。
     */
    public AttachmentVO upload(Long userId, Long taskId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "upload.fileEmpty");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.typeNotAllowed");
        }
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.MANAGE_PROJECT_CARDS);
        // 配额校验必须在落盘前执行，避免超限文件先写盘再删除
        checkQuota(userId, taskId, card.getProjectId());

        boolean image = IMAGE_EXTENSIONS.contains(extension);
        // 分目录存储：同一任务的文件聚在一起，避免单目录文件数无限膨胀
        FileStorage storage = image
                ? storageRegistry.publicImages() : storageRegistry.privateAttachments();
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + extension;
        String storedName = card.getProjectId() + "/" + taskId + "/" + fileName;
        try (InputStream content = file.getInputStream()) {
            storage.store(storedName, content);
        }

        Attachment attachment = new Attachment();
        attachment.setTaskId(taskId);
        attachment.setOriginalName(file.getOriginalFilename());
        attachment.setStoredName(storedName);
        attachment.setContentType(file.getContentType());
        attachment.setSize(file.getSize());
        attachment.setCreatedBy(userId);
        try {
            attachmentMapper.insert(attachment);
        } catch (RuntimeException e) {
            // 记录写入失败时清理已落盘的文件，避免产生无主孤儿文件
            try {
                storage.delete(storedName);
            } catch (IOException ignored) {
                // 清理失败仅丢失本次文件，不阻断异常上抛
            }
            throw e;
        }
        return toVO(attachment);
    }

    /**
     * 分页查询任务附件列表（需查看权限），按上传时间倒序；
     * 同一时间戳按 ID 倒序兜底，避免翻页时记录重复或遗漏。
     */
    public PageResult<AttachmentVO> page(Long userId, Long taskId, Long page, Long size) {
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.VIEW_PROJECT);
        long pageNo = page == null || page < 1 ? 1 : page;
        long pageSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<Attachment> result = attachmentMapper.selectPage(new Page<>(pageNo, pageSize),
                new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getTaskId, taskId)
                        .orderByDesc(Attachment::getCreateAt)
                        .orderByDesc(Attachment::getId));
        // 批量查询上传人昵称，避免逐条附件查询用户（N+1）
        Map<Long, String> nicknames = loadNicknames(result.getRecords().stream()
                .map(Attachment::getCreatedBy).filter(Objects::nonNull).distinct().toList());
        List<AttachmentVO> vos = new ArrayList<>();
        for (Attachment attachment : result.getRecords()) {
            vos.add(toVO(attachment, nicknames.get(attachment.getCreatedBy())));
        }
        return new PageResult<>(result.getTotal(), vos);
    }

    /**
     * 上传前校验数量配额（<=0 表示不限制）：单任务、单项目、单用户每日。
     */
    private void checkQuota(Long userId, Long taskId, Long projectId) {
        if (maxPerTask > 0
                && attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getTaskId, taskId)) >= maxPerTask) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.taskQuotaExceeded",
                    String.valueOf(maxPerTask));
        }
        if (maxPerProject > 0) {
            List<Long> taskIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                            .eq(Block::getProjectId, projectId))
                    .stream().map(Block::getId).toList();
            long projectCount = taskIds.isEmpty() ? 0 : attachmentMapper.selectCount(
                    new LambdaQueryWrapper<Attachment>().in(Attachment::getTaskId, taskIds));
            if (projectCount >= maxPerProject) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.projectQuotaExceeded",
                        String.valueOf(maxPerProject));
            }
        }
        if (maxPerUserPerDay > 0
                && attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                        .eq(Attachment::getCreatedBy, userId)
                        .ge(Attachment::getCreateAt, LocalDateTime.now().with(LocalTime.MIN))) >= maxPerUserPerDay) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.userQuotaExceeded",
                    String.valueOf(maxPerUserPerDay));
        }
    }

    /**
     * 删除附件（需卡片管理权限）：物理删除文件与记录，
     * 若任务描述引用该附件图片则同步移除对应 Markdown 文本。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long taskId, Long attachmentId) {
        Attachment attachment = requireAttachment(taskId, attachmentId);
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.MANAGE_PROJECT_CARDS);

        if (!STORED_NAME_PATTERN.matcher(attachment.getStoredName()).matches()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "attachment.invalidFilename");
        }
        // 删除磁盘文件（不存在则忽略，保证删除幂等；私有/公开目录均覆盖）
        try {
            deleteStoredFile(attachment.getStoredName());
        } catch (IOException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "upload.deleteFailed");
        }
        attachmentMapper.deleteById(attachmentId);
        removeDescriptionReference(card, attachment.getStoredName(), userId);
    }

    /**
     * 级联删除项目下全部任务(card)的附件：物理删除磁盘文件与记录。
     * 项目/模板软删除时调用，避免软删除后遗留孤儿附件；
     * 单个文件删除失败仅记录日志，不阻断项目删除主流程。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByProject(Long projectId) {
        List<Block> cards = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                .eq(Block::getProjectId, projectId)
                .eq(Block::getType, BlockTypes.CARD)
                .isNull(Block::getDeleteAt));
        if (cards.isEmpty()) {
            return;
        }
        List<Long> cardIds = new ArrayList<>();
        for (Block card : cards) {
            cardIds.add(card.getId());
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, cardIds));
        for (Attachment attachment : attachments) {
            if (!STORED_NAME_PATTERN.matcher(attachment.getStoredName()).matches()) {
                log.warn("项目删除级联清理：跳过异常文件名附件 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName());
                continue;
            }
            try {
                deleteStoredFile(attachment.getStoredName());
            } catch (IOException e) {
                log.warn("项目删除级联清理：删除附件文件失败 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName(), e);
            }
            attachmentMapper.deleteById(attachment.getId());
        }
    }

    /**
     * 物理清理项目下全部块的附件（含已软删块）：删除磁盘文件与记录。
     * 管理后台物理清理已软删项目时调用，返回清理的附件记录数与磁盘文件数。
     */
    public AttachmentCleanupResult physicallyDeleteByProject(Long projectId) {
        List<Long> blockIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
        if (blockIds.isEmpty()) {
            return new AttachmentCleanupResult(0, 0);
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, blockIds));
        int records = 0;
        int files = 0;
        for (Attachment attachment : attachments) {
            if (!STORED_NAME_PATTERN.matcher(attachment.getStoredName()).matches()) {
                log.warn("项目物理清理：跳过异常文件名附件 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName());
                continue;
            }
            try {
                if (deleteStoredFile(attachment.getStoredName())) {
                    files++;
                }
            } catch (IOException e) {
                log.warn("项目物理清理：删除附件文件失败 id={}, storedName={}",
                        attachment.getId(), attachment.getStoredName(), e);
            }
            attachmentMapper.deleteById(attachment.getId());
            records++;
        }
        return new AttachmentCleanupResult(records, files);
    }

    /**
     * 统计项目下全部块的附件记录数（含已软删块），供管理后台清理前预览。
     */
    public long countByProject(Long projectId) {
        List<Long> blockIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
        if (blockIds.isEmpty()) {
            return 0;
        }
        return attachmentMapper.selectCount(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, blockIds));
    }

    /**
     * 统计项目下全部块的附件磁盘文件数（含已软删块），供管理后台清理前预览。
     * 与物理清理时实际删除的文件数口径一致：仅统计存储文件名格式合法的附件。
     */
    public long countFilesByProject(Long projectId) {
        List<Long> blockIds = blockMapper.selectList(new LambdaQueryWrapper<Block>()
                        .eq(Block::getProjectId, projectId))
                .stream().map(Block::getId).toList();
        if (blockIds.isEmpty()) {
            return 0;
        }
        List<Attachment> attachments = attachmentMapper.selectList(new LambdaQueryWrapper<Attachment>()
                .in(Attachment::getTaskId, blockIds));
        return attachments.stream()
                .filter(a -> STORED_NAME_PATTERN.matcher(a.getStoredName()).matches())
                .count();
    }

    /** 附件物理清理结果：删除的附件记录数 + 磁盘文件数 */
    public record AttachmentCleanupResult(int records, int files) {
    }

    /**
     * 准备鉴权下载（需查看权限）：校验附件归属后打开对象流并返回元信息。
     */
    public DownloadPayload prepareDownload(Long userId, Long taskId, Long attachmentId) throws IOException {
        Attachment attachment = requireAttachment(taskId, attachmentId);
        Block card = getCard(taskId);
        permissionService.checkPermission(userId, card.getProjectId(), ProjectPermission.VIEW_PROJECT);
        FileStorage storage = findStorage(attachment.getStoredName());
        if (!storage.exists(attachment.getStoredName())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "attachment.notFound");
        }
        return new DownloadPayload(storage.open(attachment.getStoredName()), attachment);
    }

    /** 下载载荷：对象输入流 + 附件元信息（流由调用方负责关闭） */
    public record DownloadPayload(InputStream content, Attachment attachment) {
    }

    /** 校验附件存在且属于指定任务 */
    private Attachment requireAttachment(Long taskId, Long attachmentId) {
        Attachment attachment = attachmentMapper.selectById(attachmentId);
        if (attachment == null || !attachment.getTaskId().equals(taskId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "attachment.notFound");
        }
        return attachment;
    }

    /**
     * 定位对象所在存储桶：私有优先、公开兜底（兼容历史扁平文件与两类分目录文件）。
     */
    private FileStorage findStorage(String storedName) {
        FileStorage privateStorage = storageRegistry.privateAttachments();
        return privateStorage.exists(storedName) ? privateStorage : storageRegistry.publicImages();
    }

    /**
     * 删除对象（两个存储桶均尝试，不存在则忽略），返回是否实际删除了文件。
     * 历史扁平文件可能遗留在公开目录，因此不能只按扩展名选桶。
     */
    private boolean deleteStoredFile(String storedName) throws IOException {
        boolean deleted = false;
        for (FileStorage storage : List.of(storageRegistry.privateAttachments(), storageRegistry.publicImages())) {
            if (storage.exists(storedName)) {
                storage.delete(storedName);
                deleted = true;
            }
        }
        return deleted;
    }

    /**
     * 从任务描述中移除引用指定附件的 Markdown 图片语法（![alt](/api/v1/upload/files/xxx.ext)）。
     */
    private void removeDescriptionReference(Block card, String storedName, Long userId) {
        Map<String, Object> fields = card.getFields();
        if (fields == null) {
            return;
        }
        Object description = fields.get("description");
        if (!(description instanceof String) || ((String) description).isEmpty()) {
            return;
        }
        String url = "/api/v1/upload/files/" + storedName;
        String updated = ((String) description).replaceAll(
                "!\\[[^\\]]*\\]\\(\\Q" + url + "\\E\\)", "");
        if (updated.equals(description)) {
            return;
        }
        Map<String, Object> newFields = new HashMap<>(fields);
        newFields.put("description", updated);
        card.setFields(newFields);
        card.setModifiedBy(userId);
        blockMapper.updateById(card);
    }

    /**
     * 查询未删除的 card 块（任务）。
     */
    private Block getCard(Long taskId) {
        Block card = blockMapper.selectById(taskId);
        if (card == null || card.getDeleteAt() != null || !BlockTypes.CARD.equals(card.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "task.notFound");
        }
        return card;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private AttachmentVO toVO(Attachment attachment) {
        User creator = attachment.getCreatedBy() != null
                ? userMapper.selectById(attachment.getCreatedBy()) : null;
        return toVO(attachment, creator != null ? creator.getNickname() : null);
    }

    private AttachmentVO toVO(Attachment attachment, String createdByName) {
        AttachmentVO vo = new AttachmentVO();
        vo.setId(attachment.getId());
        vo.setTaskId(attachment.getTaskId());
        vo.setOriginalName(attachment.getOriginalName());
        // 图片保持公开静态地址（Markdown 引用与缩略图需要）；非图片走鉴权下载接口
        boolean image = IMAGE_EXTENSIONS.contains(extensionOf(attachment.getStoredName()));
        vo.setUrl(image
                ? "/api/v1/upload/files/" + attachment.getStoredName()
                : "/api/v1/tasks/" + attachment.getTaskId() + "/attachments/" + attachment.getId() + "/download");
        vo.setContentType(attachment.getContentType());
        vo.setSize(attachment.getSize());
        vo.setCreatedBy(attachment.getCreatedBy());
        vo.setCreatedAt(attachment.getCreateAt() != null
                ? attachment.getCreateAt().format(TIME_FORMATTER) : null);
        vo.setCreatedByName(createdByName);
        return vo;
    }

    /** 批量查询用户昵称（null 昵称也保留键，与单条查询语义一致） */
    private Map<Long, String> loadNicknames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> nicknames = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            nicknames.put(user.getId(), user.getNickname());
        }
        return nicknames;
    }
}
