package com.protask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.common.ResultCode;
import com.protask.dto.NotificationVO;
import com.protask.entity.Block;
import com.protask.entity.Notification;
import com.protask.entity.ProjectMember;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.NotificationMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 站内通知服务：评论 @提及产生 mention 通知；清理策略为已读即删除，
 * 用户注销时其通知库一并清空（见 AdminUserService.deleteUser）。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    /** 通知类型：评论中被 @提及 */
    public static final String TYPE_MENTION = "mention";

    /** 评论摘要最大长度（与表字段 VARCHAR(255) 预留余量） */
    private static final int SNIPPET_MAX_LENGTH = 100;

    /** 单次查询通知条数上限 */
    private static final int LIST_LIMIT_MAX = 100;

    private final NotificationMapper notificationMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final UserMapper userMapper;
    private final BlockMapper blockMapper;

    /**
     * 为评论中被 @提及的成员写入通知：去重、剔除评论者本人、仅保留该项目当前成员。
     */
    @Transactional(rollbackFor = Exception.class)
    public void notifyMentions(Long actorId, Long projectId, Long taskId, Long commentId,
                               String content, List<Long> mentionUserIds) {
        if (mentionUserIds == null || mentionUserIds.isEmpty()) {
            return;
        }
        Set<Long> candidates = mentionUserIds.stream()
                .filter(id -> id != null && !id.equals(actorId))
                .collect(Collectors.toCollection(HashSet::new));
        if (candidates.isEmpty()) {
            return;
        }
        // 仅项目当前成员可收到通知，防止前端伪造 userId 骚扰任意用户
        List<ProjectMember> members = projectMemberMapper.selectList(
                new LambdaQueryWrapper<ProjectMember>()
                        .eq(ProjectMember::getProjectId, projectId)
                        .in(ProjectMember::getUserId, candidates));
        String snippet = truncate(content);
        for (ProjectMember member : members) {
            Notification notification = new Notification();
            notification.setUserId(member.getUserId());
            notification.setType(TYPE_MENTION);
            notification.setProjectId(projectId);
            notification.setTaskId(taskId);
            notification.setCommentId(commentId);
            notification.setActorId(actorId);
            notification.setSnippet(snippet);
            notification.setIsRead(0);
            notificationMapper.insert(notification);
        }
    }

    /**
     * 查询当前用户的通知列表（按时间倒序），附带操作人昵称与任务标题。
     * 已读即删除，现存记录均为未读，unreadOnly 参数仅保留兼容。
     */
    public List<NotificationVO> listMy(Long userId, boolean unreadOnly, int limit) {
        int capped = Math.max(1, Math.min(limit, LIST_LIMIT_MAX));
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateAt)
                .last("LIMIT " + capped);
        List<Notification> notifications = notificationMapper.selectList(wrapper);
        if (notifications.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, String> actorNames = userMapper.selectBatchIds(
                        notifications.stream().map(Notification::getActorId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(User::getId, User::getNickname));
        Map<Long, String> taskTitles = blockMapper.selectBatchIds(
                        notifications.stream().map(Notification::getTaskId).collect(Collectors.toSet())).stream()
                .filter(b -> BlockTypes.CARD.equals(b.getType()) && b.getDeleteAt() == null)
                .collect(Collectors.toMap(Block::getId, Block::getTitle));
        return notifications.stream().map(n -> toVO(n, actorNames, taskTitles)).collect(Collectors.toList());
    }

    /**
     * 当前用户未读通知数（顶栏铃铛角标）。已读即删除，现存记录均为未读。
     */
    public long unreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId));
    }

    /**
     * 已读单条通知即删除（仅本人可操作）。
     */
    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification == null || !userId.equals(notification.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "notification.notFound");
        }
        notificationMapper.deleteById(notificationId);
    }

    /**
     * 全部已读即清空当前用户的通知。
     */
    public void markAllRead(Long userId) {
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId));
    }

    private NotificationVO toVO(Notification n, Map<Long, String> actorNames, Map<Long, String> taskTitles) {
        NotificationVO vo = new NotificationVO();
        vo.setId(n.getId());
        vo.setType(n.getType());
        vo.setProjectId(n.getProjectId());
        vo.setTaskId(n.getTaskId());
        vo.setCommentId(n.getCommentId());
        vo.setActorId(n.getActorId());
        vo.setActorName(actorNames.get(n.getActorId()));
        vo.setTaskTitle(taskTitles.get(n.getTaskId()));
        vo.setSnippet(n.getSnippet());
        vo.setIsRead(n.getIsRead() == 1);
        vo.setCreateAt(n.getCreateAt());
        return vo;
    }

    private String truncate(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.length() <= SNIPPET_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, SNIPPET_MAX_LENGTH) + "…";
    }
}
