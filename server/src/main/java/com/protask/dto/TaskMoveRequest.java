package com.protask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 任务同级重排请求（上移/下移，WBS 随之重编号）。
 */
@Data
public class TaskMoveRequest {

    @NotBlank(message = "{validation.taskMoveDirection.notBlank}")
    @Pattern(regexp = "up|down", message = "{validation.taskMoveDirection.pattern}")
    private String direction;
}