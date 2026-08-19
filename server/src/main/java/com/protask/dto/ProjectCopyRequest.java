package com.protask.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 复制项目请求（字段可选，缺省沿用原项目值；名称缺省为「原名称 + 副本后缀」）。
 */
@Data
public class ProjectCopyRequest {

    /** 新项目名称（默认取原项目名称 + 副本后缀） */
    @Size(max = 100, message = "{validation.projectName.size}")
    private String name;

    /** 新项目描述（默认沿用原项目描述） */
    @Size(max = 500, message = "{validation.projectDescription.size}")
    private String description;

    /** 新项目类型（默认沿用原项目类型） */
    private String type;
}
