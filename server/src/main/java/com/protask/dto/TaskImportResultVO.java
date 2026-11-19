package com.protask.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel 导入结果：errors 非空时表示校验失败、未写入任何任务（全量校验后单事务写入）。
 */
@Data
public class TaskImportResultVO {

    /** 新建任务数 */
    private int created;

    /** 新建依赖数（仅 WBS 项目） */
    private int dependencyCount;

    /** 逐行校验错误，非空则整体未导入 */
    private List<TaskImportErrorVO> errors = new ArrayList<>();

    /** 无法识别而被忽略的列头 */
    private List<String> ignoredColumns = new ArrayList<>();
}