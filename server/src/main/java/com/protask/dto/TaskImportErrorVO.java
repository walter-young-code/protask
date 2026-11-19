package com.protask.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel 导入逐行错误（row 为 Excel 行号，含表头行；message 已按请求语言解析）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskImportErrorVO {

    private int row;

    private String message;
}