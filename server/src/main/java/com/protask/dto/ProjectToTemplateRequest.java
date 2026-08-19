package com.protask.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 将现有项目转为模板请求（字段可选，缺省沿用原项目值）。
 */
@Data
public class ProjectToTemplateRequest {

    /** 模板名称（默认取原项目名称） */
    @Size(max = 100, message = "{validation.projectName.size}")
    private String name;

    /** 模板描述（默认取原项目描述） */
    @Size(max = 500, message = "{validation.projectDescription.size}")
    private String description;
}
