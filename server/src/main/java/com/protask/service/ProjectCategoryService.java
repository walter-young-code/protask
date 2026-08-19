package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.CategoryRequest;
import com.protask.dto.CategoryVO;
import com.protask.entity.Project;
import com.protask.entity.ProjectCategory;
import com.protask.mapper.ProjectCategoryMapper;
import com.protask.mapper.ProjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 项目分类服务：分类私有（仅创建者可见，名称在同一创建者内唯一）。
 */
@Service
@RequiredArgsConstructor
public class ProjectCategoryService {

    private final ProjectCategoryMapper categoryMapper;
    private final ProjectMapper projectMapper;

    /** 查询当前用户的分类（按创建时间升序） */
    public List<CategoryVO> list(Long userId) {
        List<ProjectCategory> categories = categoryMapper.selectList(
                new LambdaQueryWrapper<ProjectCategory>()
                        .eq(ProjectCategory::getCreatedBy, userId)
                        .orderByAsc(ProjectCategory::getCreatedAt));
        return categories.stream().map(category -> {
            CategoryVO vo = new CategoryVO();
            BeanUtils.copyProperties(category, vo);
            return vo;
        }).toList();
    }

    /** 新建分类（名称在同一创建者内唯一） */
    @Transactional(rollbackFor = Exception.class)
    public CategoryVO create(Long userId, CategoryRequest request) {
        String name = request.getName().trim();
        Long exists = categoryMapper.selectCount(
                new LambdaQueryWrapper<ProjectCategory>()
                        .eq(ProjectCategory::getName, name)
                        .eq(ProjectCategory::getCreatedBy, userId));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "category.name.exists");
        }
        ProjectCategory category = new ProjectCategory();
        category.setName(name);
        category.setCreatedBy(userId);
        categoryMapper.insert(category);
        CategoryVO vo = new CategoryVO();
        BeanUtils.copyProperties(category, vo);
        return vo;
    }

    /** 修改分类名称（仅创建者，名称在同一创建者内唯一） */
    @Transactional(rollbackFor = Exception.class)
    public CategoryVO update(Long userId, Long id, CategoryRequest request) {
        ProjectCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "category.notFound");
        }
        if (!category.getCreatedBy().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "category.updateDenied");
        }
        String name = request.getName().trim();
        Long exists = categoryMapper.selectCount(new LambdaQueryWrapper<ProjectCategory>()
                .eq(ProjectCategory::getName, name)
                .eq(ProjectCategory::getCreatedBy, userId)
                .ne(ProjectCategory::getId, id));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "category.name.exists");
        }
        category.setName(name);
        categoryMapper.updateById(category);
        CategoryVO vo = new CategoryVO();
        BeanUtils.copyProperties(category, vo);
        return vo;
    }

    /** 删除分类（仅创建者）：引用该分类的项目归入未分类 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        ProjectCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "category.notFound");
        }
        if (!category.getCreatedBy().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "category.deleteDenied");
        }
        categoryMapper.deleteById(id);
        // 显式 SQL 置空，避免 MyBatis-Plus 对 null 字段的默认忽略
        projectMapper.update(null, new LambdaUpdateWrapper<Project>()
                .eq(Project::getCategoryId, id)
                .set(Project::getCategoryId, null));
    }
}
