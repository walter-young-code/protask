package com.protask.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目实体。
 */
@Data
@TableName("project")
public class Project {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 项目名称 */
    private String name;

    /** 项目描述 */
    private String description;

    /** 项目类型: O 公开(非成员仅可查看), P 私有 */
    private String type;

    /** 分享链接标识(UUID，NULL=未开启分享；公开项目非成员凭此只读访问) */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String shareId;

    /** 日历订阅凭证(UUID，NULL=未开启订阅；ICS 订阅地址凭此匿名访问) */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String calendarToken;

    /** 项目图标 */
    private String icon;

    /** 卡片动态属性定义模板(JSON 数组) */
    private String cardProperties;

    /** 项目扩展属性(JSON 对象) */
    private String properties;

    /** 是否项目模板: false 普通项目, true 模板（仅通过模板接口读取） */
    private Boolean isTemplate;

    /** 项目负责人(用户ID)，等同项目 admin 角色 */
    private Long ownerId;

    /** 创建人(用户ID) */
    private Long createdBy;

    /** 最后修改人(用户ID) */
    private Long modifiedBy;

    /** 状态: active/archived */
    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 删除时间(软删除标记)，为 null 表示未删除 */
    private LocalDateTime deleteAt;
}
