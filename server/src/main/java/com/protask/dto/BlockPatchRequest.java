package com.protask.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 块局部更新请求（对齐 Focalboard BlockPatch：仅应用提供的字段）。
 */
@Data
public class BlockPatchRequest {

    /** 父块ID，null 表示不修改 */
    private Long parentId;

    @Size(max = 500, message = "{validation.blockTitle.size}")
    private String title;

    /** 需要合并更新的动态字段 */
    private Map<String, Object> updatedFields;

    /** 需要删除的动态字段 key 列表 */
    private List<String> deletedFields;
}
