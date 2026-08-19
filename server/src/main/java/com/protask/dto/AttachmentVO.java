package com.protask.dto;

import lombok.Data;

/**
 * 附件视图：图片附件 url 为公开静态地址（/api/v1/upload/files/**），
 * 非图片附件 url 为鉴权下载接口地址（需携带 token）。
 */
@Data
public class AttachmentVO {

    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 原始文件名 */
    private String originalName;

    /** 可访问地址（图片为公开静态资源，非图片为鉴权下载接口） */
    private String url;

    /** MIME类型 */
    private String contentType;

    /** 文件大小(字节) */
    private Long size;

    /** 上传人(用户ID) */
    private Long createdBy;

    /** 上传人昵称 */
    private String createdByName;

    private String createdAt;
}
