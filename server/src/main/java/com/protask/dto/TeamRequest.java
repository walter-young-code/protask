package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理后台创建/更新团队请求。
 */
@Data
public class TeamRequest {

    @NotBlank(message = "{team.name.required}")
    @Size(max = 50, message = "{team.name.size}")
    private String name;
}
