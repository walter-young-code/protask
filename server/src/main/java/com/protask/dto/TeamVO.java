package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理后台团队视图对象（含成员数）。
 */
@Data
public class TeamVO {

    private Long id;

    private String name;

    /** 团队成员数 */
    private Long memberCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
