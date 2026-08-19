package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台更新项目模板请求。
 */
@Data
public class ProjectTemplateUpdateRequest {

    /** 模板名称 */
    @NotBlank(message = "{validation.projectName.notBlank}")
    @Size(max = 100, message = "{validation.projectName.size}")
    private String name;

    /** 模板描述 */
    @Size(max = 500, message = "{validation.projectDescription.size}")
    private String description;

    /** 模板图标 */
    @Size(max = 255, message = "{validation.projectIcon.size}")
    private String icon;
}
