package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 团队实体。
 */
@Data
@TableName("team")
public class Team {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 团队名称（唯一） */
    private String name;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
