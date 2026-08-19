package com.protask.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新项目卡片动态属性模板请求（自定义属性整体替换，内置属性由后端保留）。
 */
@Data
public class CardPropertiesRequest {

    @NotNull(message = "{validation.cardProperties.notNull}")
    @Valid
    private List<CardPropertyDef> properties;

    /** 单个属性定义（与 project.card_properties 中的模板项同构） */
    @Data
    public static class CardPropertyDef {

        @NotBlank(message = "{validation.cardProperty.id.notBlank}")
        private String id;

        @NotBlank(message = "{validation.cardProperty.name.notBlank}")
        @Size(max = 50, message = "{validation.cardProperty.name.size}")
        private String name;

        @NotBlank(message = "{validation.cardProperty.type.notBlank}")
        @Pattern(regexp = "^(text|select|date|number|multiSelect|checkbox|member)$",
                message = "{validation.cardProperty.type.pattern}")
        private String type;

        /** select/multiSelect 的选项列表 */
        @Valid
        private List<CardPropertyOption> options;
    }

    /** 属性选项 */
    @Data
    public static class CardPropertyOption {

        @NotBlank(message = "{validation.cardPropertyOption.id.notBlank}")
        private String id;

        @NotBlank(message = "{validation.cardPropertyOption.value.notBlank}")
        @Size(max = 50, message = "{validation.cardPropertyOption.value.size}")
        private String value;

        private String color;
    }
}
