package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建/更新项目请求。
 */
@Data
public class ProjectRequest {

    @NotBlank(message = "{validation.projectName.notBlank}")
    @Size(max = 100, message = "{validation.projectName.size}")
    private String name;

    @Size(max = 500, message = "{validation.projectDescription.size}")
    private String description;

    /** 项目类型: O 公开, P 私有，null 表示不修改/默认私有 */
    @Pattern(regexp = "^(O|P)$", message = "{validation.projectType.pattern}")
    private String type;

    /** 项目分类ID：null 不修改；0 清除分类；>0 设为指定分类 */
    private Long categoryId;

    /** 项目图标 */
    @Size(max = 255, message = "{validation.projectIcon.size}")
    private String icon;
}
