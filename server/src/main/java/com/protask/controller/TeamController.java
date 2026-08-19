package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.PageResult;
import com.protask.dto.TeamRequest;
import com.protask.dto.TeamVO;
import com.protask.service.TeamService;
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
 * 管理后台团队接口（仅系统管理员可访问）。
 */
@RestController
@RequestMapping("/api/v1/admin/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    /** 分页查询团队（名称模糊匹配，含成员数） */
    @GetMapping
    public Result<PageResult<TeamVO>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long page,
            @RequestParam(required = false) Long size) {
        return Result.ok(teamService.page(StpUtil.getLoginIdAsLong(), keyword, page, size));
    }

    /** 全量团队列表（含成员数），供用户管理下拉选择 */
    @GetMapping("/all")
    public Result<List<TeamVO>> listAll() {
        return Result.ok(teamService.listAll(StpUtil.getLoginIdAsLong()));
    }

    /** 创建团队 */
    @PostMapping
    public Result<TeamVO> create(@Valid @RequestBody TeamRequest request) {
        return Result.ok(teamService.create(StpUtil.getLoginIdAsLong(), request));
    }

    /** 重命名团队 */
    @PutMapping("/{id}")
    public Result<TeamVO> update(@PathVariable Long id, @Valid @RequestBody TeamRequest request) {
        return Result.ok(teamService.update(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 删除团队（团队下用户 team_id 置空） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        teamService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
