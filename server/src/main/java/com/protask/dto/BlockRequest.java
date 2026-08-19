package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建块请求（对齐 Focalboard Block 模型）。
 */
@Data
public class BlockRequest {

    /** 块类型: card/view/text/comment */
    @NotBlank(message = "{validation.blockType.notBlank}")
    @Pattern(regexp = "^(card|view|text|comment)$", message = "{validation.blockType.pattern}")
    private String type;

    /** 父块ID，可为空 */
    private Long parentId;

    @Size(max = 500, message = "{validation.blockTitle.size}")
    private String title;

    /** 块动态字段(card: properties/contentOrder; text: content 等) */
    private Map<String, Object> fields;

    /** 评论中被 @提及的成员用户 ID（仅 comment 类型生效，后端校验项目成员身份） */
    private List<Long> mentionUserIds;
}
