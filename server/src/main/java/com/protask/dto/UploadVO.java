package com.protask.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 上传结果。
 */
@Data
@AllArgsConstructor
public class UploadVO {

    /** 图片访问 URL（相对路径，经 /api/v1/upload/files/** 静态映射） */
    private String url;
}
