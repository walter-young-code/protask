package com.protask.dto;

import lombok.Data;

/**
 * 已删除项目物理清理视图：管理后台按日期清理时返回的统计结果。
 */
@Data
public class ProjectCleanupVO {

    /** 清理的项目数 */
    private int projects;

    /** 物理删除的块数（任务/视图/评论） */
    private int blocks;

    /** 物理删除的附件记录数 */
    private int attachments;

    /** 删除的附件磁盘文件数 */
    private int attachmentFiles;

    /** 删除的项目成员关系数 */
    private int members;

    /** 删除的站内通知数 */
    private int notifications;
}
