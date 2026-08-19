package com.protask.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.protask.common.Result;
import com.protask.dto.CategoryRequest;
import com.protask.dto.CategoryVO;
import com.protask.service.ProjectCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目分类接口。
 */
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ProjectCategoryService categoryService;

    /** 查询当前用户的分类 */
    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.ok(categoryService.list(StpUtil.getLoginIdAsLong()));
    }

    /** 新建分类 */
    @PostMapping
    public Result<CategoryVO> create(@Valid @RequestBody CategoryRequest request) {
        return Result.ok(categoryService.create(StpUtil.getLoginIdAsLong(), request));
    }

    /** 修改分类名称（仅创建者） */
    @PutMapping("/{id}")
    public Result<CategoryVO> update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return Result.ok(categoryService.update(StpUtil.getLoginIdAsLong(), id, request));
    }

    /** 删除分类（仅创建者，项目引用自动置空） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
