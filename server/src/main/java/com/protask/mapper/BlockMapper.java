package com.protask.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.protask.entity.Block;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 块模型 Mapper。
 */
@Mapper
public interface BlockMapper extends BaseMapper<Block> {

    /**
     * 在指定项目范围内按关键词搜索卡片（标题或描述包含关键词），按更新时间倒序。
     * 注意：自定义 SQL 不会套用实体 autoResultMap，需显式声明 fields 列的 JSON TypeHandler。
     */
    @Select("""
            <script>
            SELECT b.* FROM block b
            WHERE b.type = 'card' AND b.delete_at IS NULL
              AND (b.title LIKE CONCAT('%', #{keyword}, '%')
                   OR JSON_UNQUOTE(JSON_EXTRACT(b.fields, '$.description')) LIKE CONCAT('%', #{keyword}, '%'))
              AND b.project_id IN
              <foreach collection="projectIds" item="pid" open="(" separator="," close=")">#{pid}</foreach>
            ORDER BY b.update_at DESC
            LIMIT #{limit}
            </script>
            """)
    @Results({
            @Result(column = "fields", property = "fields", typeHandler = JacksonTypeHandler.class)
    })
    List<Block> searchCards(@Param("keyword") String keyword,
                            @Param("projectIds") List<Long> projectIds,
                            @Param("limit") int limit);
}
