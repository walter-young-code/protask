package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 图片上传归属实体：记录 Markdown 插图文件的上传者，删除时校验归属。
 */
@Data
@TableName("upload_image")
public class UploadImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 存储文件名(UUID.ext) */
    private String storedName;

    /** 上传人(用户ID) */
    private Long createdBy;

    private LocalDateTime createdAt;
}
