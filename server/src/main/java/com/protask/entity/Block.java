package com.protask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDate;
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

    /** 父任务ID(仅添加 WBS 编码属性的项目生效，非 WBS 项目恒为 null) */
    // 置根需写 NULL：默认 NOT_NULL 策略会跳过 null 致「设为根任务」静默失败；所有 updateById 均基于全量加载实体，ALWAYS 安全
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long parentTaskId;

    /** 同级排序序号(WBS 项目树序重排用) */
    private Integer sortOrder;

    /** 里程碑标记(false=普通任务,true=里程碑；甘特菱形渲染与 MSPDI 导出消费) */
    private Boolean milestone;

    /** 计划模式(manual=手动/auto=自动，仅 WBS 项目生效；自动任务日期由前置依赖驱动) */
    private String scheduleMode;

    /** 显式工期(自然日含首日)；null=按起止日期推导。置空需写 NULL，同 parentTaskId 用 ALWAYS */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer durationDays;

    /** 自动计划开始约束(不得早于)；null=纯前置驱动。清除约束需写 NULL，用 ALWAYS */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDate startConstraint;

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
