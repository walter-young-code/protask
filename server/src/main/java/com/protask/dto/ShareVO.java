package com.protask.dto;

import lombok.Data;

/**
 * 项目分享视图：分享凭证与完整链接。
 * shareUrl 基于请求 Origin 拼接，供复制分享链接用。
 */
@Data
public class ShareVO {

    /** 分享凭证(UUID，null=未开启分享) */
    private String shareId;

    /** 分享链接（null=未开启分享） */
    private String shareUrl;
}
