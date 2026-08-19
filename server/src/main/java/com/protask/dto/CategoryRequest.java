package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建项目分类请求。
 */
@Data
public class CategoryRequest {

    @NotBlank(message = "{validation.categoryName.notBlank}")
    @Size(max = 50, message = "{validation.categoryName.size}")
    private String name;
}
