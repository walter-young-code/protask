package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 从模板创建项目请求：模板 ID + 项目基本信息（缺省字段继承模板）。
 */
@Data
public class ProjectTemplateRequest {

    /** 来源模板 ID */
    @NotNull(message = "{validation.templateId.notNull}")
    private Long templateId;

    @NotBlank(message = "{validation.projectName.notBlank}")
    @Size(max = 100, message = "{validation.projectName.size}")
    private String name;

    @Size(max = 500, message = "{validation.projectDescription.size}")
    private String description;

    /** 项目类型: O 公开, P 私有，null 表示默认私有 */
    @Pattern(regexp = "^(O|P)$", message = "{validation.projectType.pattern}")
    private String type;

    /** 项目分类ID：null 表示不设置分类；0 表示无分类；>0 设为指定分类 */
    private Long categoryId;

    /** 项目图标，null 表示继承模板图标 */
    @Size(max = 255, message = "{validation.projectIcon.size}")
    private String icon;
}
