package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一块模型实体（对齐 Focalboard Block：card/view/text/comment）。
 * 卡片任务即 type=card 的块，动态属性存于 fields.properties。
 */
@Data
@TableName(value = "block", autoResultMap = true)
public class Block {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属项目ID */
    private Long projectId;

    /** 父块ID(卡片内容块的父为卡片) */
    private Long parentId;

    /** 块结构版本号 */
    private Long schemaVer;

    /** 块类型: card/view/text/comment */
    private String type;

    /** 块标题 */
    private String title;

    /** 块动态字段(card: properties/contentOrder; text: content 等) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> fields;

    /** 创建人(用户ID) */
    private Long createdBy;

    /** 最后修改人(用户ID) */
    private Long modifiedBy;

    private LocalDateTime createAt;

    private LocalDateTime updateAt;

    /** 删除时间(软删除标记)，为 null 表示未删除 */
    private LocalDateTime deleteAt;
}
