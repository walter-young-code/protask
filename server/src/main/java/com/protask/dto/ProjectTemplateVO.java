package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 项目模板视图（模板库只读数据，含预览所需属性与示例卡片）。
 */
@Data
public class ProjectTemplateVO {

    private Long id;

    private String name;

    private String description;

    /** 项目图标 */
    private String icon;

    /** 卡片动态属性定义模板(JSON 数组字符串) */
    private String cardProperties;

    /** 模板创建时间 */
    private LocalDateTime createdAt;

    /** 示例卡片（用于模板预览） */
    private List<SampleCardVO> sampleCards;

    /** 示例卡片视图 */
    @Data
    public static class SampleCardVO {

        private String title;

        /** 卡片图标 */
        private String icon;

        /** 卡片动态属性值 */
        private Map<String, Object> properties;
    }
}
