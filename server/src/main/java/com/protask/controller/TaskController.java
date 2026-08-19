package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.DashboardVO;
import com.protask.dto.TaskRequest;
import com.protask.dto.TaskVO;
import com.protask.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务接口。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /** 查询项目下的任务列表 */
    @GetMapping("/projects/{projectId}/tasks")
    public Result<List<TaskVO>> listTasks(@PathVariable Long projectId) {
        return Result.ok(taskService.listTasks(StpUtil.getLoginIdAsLong(), projectId));
    }

    /** 跨项目仪表盘：当前用户可见项目的任务统计与分类列表 */
    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard() {
        return Result.ok(taskService.getDashboard(StpUtil.getLoginIdAsLong()));
    }

    /** 全局搜索当前用户可见项目内的任务 */
    @GetMapping("/tasks/search")
    public Result<List<TaskVO>> searchTasks(@RequestParam(required = false, defaultValue = "") String keyword) {
        return Result.ok(taskService.searchTasks(StpUtil.getLoginIdAsLong(), keyword));
    }

    /** 在项目下创建任务 */
    @PostMapping("/projects/{projectId}/tasks")
    public Result<TaskVO> create(@PathVariable Long projectId, @Valid @RequestBody TaskRequest request) {
        return Result.ok(taskService.createTask(StpUtil.getLoginIdAsLong(), projectId, request));
    }

    /** 更新任务 */
    @PutMapping("/tasks/{id}")
    public Result<TaskVO> update(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
        return Result.ok(taskService.updateTask(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 删除任务 */
    @DeleteMapping("/tasks/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.deleteTask(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
