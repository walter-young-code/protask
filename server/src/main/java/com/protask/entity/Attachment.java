package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务附件实体：文件存储于 uploads 目录（stored_name 为 UUID 文件名），
 * 删除附件时物理删除文件与记录。
 */
@Data
@TableName("attachment")
public class Attachment {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务(card块)ID */
    private Long taskId;

    /** 原始文件名 */
    private String originalName;

    /** 存储文件名(UUID.ext) */
    private String storedName;

    /** MIME类型 */
    private String contentType;

    /** 文件大小(字节) */
    private Long size;

    /** 上传人(用户ID) */
    private Long createdBy;

    private LocalDateTime createAt;
}
