package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.ProjectTemplateVO;
import com.protask.service.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目模板库接口：模板为只读共享数据（is_template=1 的项目），
 * 登录用户即可浏览；从模板创建项目走 /api/v1/projects/from-template。
 */
@RestController
@RequestMapping("/api/v1/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final ProjectService projectService;

    /** 查询模板库（含示例卡片，用于模板预览） */
    @GetMapping
    public Result<List<ProjectTemplateVO>> listTemplates() {
        StpUtil.checkLogin();
        return Result.ok(projectService.listTemplates());
    }
}
