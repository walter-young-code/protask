package com.protask.dto;

import lombok.Data;

/**
 * 管理后台用户分页查询参数。
 */
@Data
public class AdminUserQuery {

    /** 关键字：模糊匹配用户名/昵称/邮箱 */
    private String keyword;

    /** 状态过滤: 1 启用, 0 禁用, null 全部 */
    private Integer status;

    private Long page = 1L;

    private Long size = 10L;
}
