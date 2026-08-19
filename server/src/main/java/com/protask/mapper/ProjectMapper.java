package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.protask.dto.ProjectVO;
import com.protask.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 项目 Mapper。
 */
@Mapper
public interface ProjectMapper extends BaseMapper<Project> {

    /**
     * 查询用户参与的所有项目（含负责人昵称与当前用户在项目中的角色），
     * 仅返回正常状态项目，归档项目由管理后台统一管理。
     */
    @Select("""
            SELECT p.id, p.name, p.description, p.type, p.icon,
                   p.category_id, c.name AS category_name,
                   p.card_properties, p.owner_id, p.status, p.is_template,
                   p.created_at, p.updated_at,
                   u.nickname AS owner_name, pm.role AS my_role
            FROM project p
            JOIN project_member pm ON pm.project_id = p.id
            LEFT JOIN sys_user u ON u.id = p.owner_id
            LEFT JOIN project_category c ON c.id = p.category_id
            WHERE pm.user_id = #{userId} AND p.delete_at IS NULL AND p.is_template = 0
              AND p.status = 'active'
            ORDER BY p.updated_at DESC
            """)
    List<ProjectVO> selectMyProjects(@Param("userId") Long userId);

    /**
     * 管理后台分页查询归档项目（含负责人昵称，关键字模糊匹配名称）。
     */
    @Select("""
            <script>
            SELECT p.id, p.name, p.description, p.type, p.icon,
                   p.category_id, c.name AS category_name,
                   p.card_properties, p.owner_id, p.status, p.is_template,
                   p.created_at, p.updated_at,
                   u.nickname AS owner_name
            FROM project p
            LEFT JOIN sys_user u ON u.id = p.owner_id
            LEFT JOIN project_category c ON c.id = p.category_id
            WHERE p.delete_at IS NULL AND p.is_template = 0 AND p.status = 'archived'
            <if test="keyword != null and keyword != ''">
                AND p.name LIKE CONCAT('%', #{keyword}, '%')
            </if>
            ORDER BY p.updated_at DESC, p.id DESC
            </script>
            """)
    List<ProjectVO> selectArchivedProjects(IPage<ProjectVO> page, @Param("keyword") String keyword);
}
