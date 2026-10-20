package com.protask.storage;

/**
 * 存储桶注册表：公开图片（静态映射、可匿名访问）与私有附件（仅鉴权下载）两个桶。
 * 通过构造注入便于单测替换为临时目录。
 */
public class StorageRegistry {

    private final FileStorage publicImages;
    private final FileStorage privateAttachments;

    public StorageRegistry(FileStorage publicImages, FileStorage privateAttachments) {
        this.publicImages = publicImages;
        this.privateAttachments = privateAttachments;
    }

    /** 公开图片桶：Markdown 插图与图片附件（<img> 无法携带 token，需公开访问） */
    public FileStorage publicImages() {
        return publicImages;
    }

    /** 私有附件桶：文档/压缩包等，仅通过鉴权下载接口访问 */
    public FileStorage privateAttachments() {
        return privateAttachments;
    }
}
