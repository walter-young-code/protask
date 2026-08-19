package com.protask.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 块视图对象（含创建人/修改人昵称）。
 */
@Data
public class BlockVO {

    private Long id;

    private Long projectId;

    private Long parentId;

    private Long schemaVer;

    private String type;

    private String title;

    /** 块动态字段 */
    private Map<String, Object> fields;

    private Long createdBy;

    /** 创建人昵称 */
    private String createdByName;

    private Long modifiedBy;

    /** 修改人昵称 */
    private String modifiedByName;

    private LocalDateTime createAt;

    private LocalDateTime updateAt;
}
