package com.protask.service;

import com.protask.common.BlockTypes;
import com.protask.common.BusinessException;
import com.protask.dto.NotificationVO;
import com.protask.entity.Block;
import com.protask.entity.Notification;
import com.protask.entity.ProjectMember;
import com.protask.entity.User;
import com.protask.mapper.BlockMapper;
import com.protask.mapper.NotificationMapper;
import com.protask.mapper.ProjectMemberMapper;
import com.protask.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NotificationService 单元测试：提及通知写入规则与已读即删除的清理策略。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Long ACTOR_ID = 1L;
    private static final Long PROJECT_ID = 100L;
    private static final Long TASK_ID = 200L;
    private static final Long COMMENT_ID = 300L;

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private BlockMapper blockMapper;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationService(notificationMapper, projectMemberMapper, userMapper, blockMapper);
    }

    private ProjectMember member(Long userId) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(PROJECT_ID);
        member.setUserId(userId);
        member.setRole("editor");
        return member;
    }

    @Test
    void notifyMentionsDeduplicatesExcludesSelfAndFiltersNonMembers() {
        // 提及列表含重复(2)、评论者本人(1)与非成员(3)，仅成员 2 应收到通知
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(member(2L)));

        notificationService.notifyMentions(ACTOR_ID, PROJECT_ID, TASK_ID, COMMENT_ID,
                "@张三 请看一下", List.of(2L, 2L, 3L, 1L));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper, times(1)).insert(captor.capture());
        Notification inserted = captor.getValue();
        assertEquals(2L, inserted.getUserId());
        assertEquals(NotificationService.TYPE_MENTION, inserted.getType());
        assertEquals(TASK_ID, inserted.getTaskId());
        assertEquals(COMMENT_ID, inserted.getCommentId());
        assertEquals(ACTOR_ID, inserted.getActorId());
        assertEquals(0, inserted.getIsRead());
    }

    @Test
    void notifyMentionsEmptyOrSelfOnlySkipsInsert() {
        notificationService.notifyMentions(ACTOR_ID, PROJECT_ID, TASK_ID, COMMENT_ID, "hi", List.of());
        notificationService.notifyMentions(ACTOR_ID, PROJECT_ID, TASK_ID, COMMENT_ID, "@me", List.of(ACTOR_ID));
        verifyNoInteractions(notificationMapper, projectMemberMapper);
    }

    @Test
    void notifyMentionsTruncatesLongSnippet() {
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(member(2L)));
        String longContent = "长".repeat(150);

        notificationService.notifyMentions(ACTOR_ID, PROJECT_ID, TASK_ID, COMMENT_ID,
                longContent, List.of(2L));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(101, captor.getValue().getSnippet().length()); // 100 字 + 省略号
        assertTrue(captor.getValue().getSnippet().endsWith("…"));
    }

    @Test
    void listMyEnrichesActorNameAndTaskTitle() {
        Notification notification = new Notification();
        notification.setId(10L);
        notification.setUserId(2L);
        notification.setType(NotificationService.TYPE_MENTION);
        notification.setProjectId(PROJECT_ID);
        notification.setTaskId(TASK_ID);
        notification.setCommentId(COMMENT_ID);
        notification.setActorId(ACTOR_ID);
        notification.setSnippet("请看一下");
        notification.setIsRead(0);
        notification.setCreateAt(LocalDateTime.now());
        when(notificationMapper.selectList(any())).thenReturn(List.of(notification));

        User actor = new User();
        actor.setId(ACTOR_ID);
        actor.setNickname("alice");
        lenient().when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(actor));

        Block task = new Block();
        task.setId(TASK_ID);
        task.setType(BlockTypes.CARD);
        task.setTitle("任务A");
        lenient().when(blockMapper.selectBatchIds(anyCollection())).thenReturn(List.of(task));

        List<NotificationVO> result = notificationService.listMy(2L, false, 50);

        assertEquals(1, result.size());
        NotificationVO vo = result.get(0);
        assertEquals("alice", vo.getActorName());
        assertEquals("任务A", vo.getTaskTitle());
        assertFalse(vo.getIsRead());
    }

    @Test
    void markReadOwnNotificationDeletesIt() {
        Notification notification = new Notification();
        notification.setId(10L);
        notification.setUserId(2L);
        notification.setIsRead(0);
        when(notificationMapper.selectById(10L)).thenReturn(notification);

        notificationService.markRead(2L, 10L);

        // 已读即删除：物理删除记录而非更新标记
        verify(notificationMapper).deleteById(10L);
        verify(notificationMapper, never()).updateById(any(Notification.class));
    }

    @Test
    void markReadOthersNotificationThrowsNotFound() {
        Notification notification = new Notification();
        notification.setId(10L);
        notification.setUserId(3L);
        notification.setIsRead(0);
        when(notificationMapper.selectById(10L)).thenReturn(notification);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> notificationService.markRead(2L, 10L));
        assertEquals("notification.notFound", ex.getMessageKey());
        verify(notificationMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void unreadCountDelegatesToMapper() {
        when(notificationMapper.selectCount(any())).thenReturn(3L);
        assertEquals(3L, notificationService.unreadCount(2L));
    }

    @Test
    void markAllReadClearsAllUserNotifications() {
        notificationService.markAllRead(2L);
        verify(notificationMapper).delete(any());
    }
}
