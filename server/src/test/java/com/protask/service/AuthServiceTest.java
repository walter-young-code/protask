package com.protask.service;

import com.protask.common.BusinessException;
import com.protask.dto.ChangePasswordRequest;
import com.protask.dto.RegisterRequest;
import com.protask.entity.InviteToken;
import com.protask.entity.User;
import com.protask.mapper.InviteTokenMapper;
import com.protask.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试：
 * - 注册需携带有效邀请链接凭证，注册成功后凭证作废；
 * - 修改密码需校验旧密码，成功后更新密文并踢出其余会话。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long INVITE_ID = 10L;
    private static final String INVITE_TOKEN = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

    @Mock
    private UserMapper userMapper;

    @Mock
    private InviteTokenMapper inviteTokenMapper;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userMapper, inviteTokenMapper, passwordEncoder);
    }

    private User userWithPassword(String rawPassword) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setStatus(1);
        return user;
    }

    private ChangePasswordRequest request(String oldPassword, String newPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    /** 未使用且未过期的邀请凭证 */
    private InviteToken validInvite() {
        InviteToken invite = new InviteToken();
        invite.setId(INVITE_ID);
        invite.setToken(INVITE_TOKEN);
        invite.setInvitedBy(2L);
        invite.setStatus(0);
        invite.setExpiresAt(LocalDateTime.now().plusDays(7));
        return invite;
    }

    private RegisterRequest registerRequest(String username) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("pass-123");
        request.setInviteToken(INVITE_TOKEN);
        return request;
    }

    // ---------- 注册（邀请制） ----------

    @Test
    void registerRejectsMissingInviteToken() {
        RegisterRequest request = registerRequest("bob");
        request.setInviteToken(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(request));
        assertEquals("auth.invite.required", ex.getMessageKey());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsUnknownInviteToken() {
        when(inviteTokenMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest("bob")));
        assertEquals("auth.invite.invalid", ex.getMessageKey());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsUsedInviteToken() {
        InviteToken used = validInvite();
        used.setStatus(1);
        when(inviteTokenMapper.selectOne(any())).thenReturn(used);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest("bob")));
        assertEquals("auth.invite.used", ex.getMessageKey());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerRejectsExpiredInviteToken() {
        InviteToken expired = validInvite();
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(inviteTokenMapper.selectOne(any())).thenReturn(expired);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.register(registerRequest("bob")));
        assertEquals("auth.invite.expired", ex.getMessageKey());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void registerSucceedsWithValidInviteAndConsumesIt() {
        when(inviteTokenMapper.selectOne(any())).thenReturn(validInvite());
        when(userMapper.selectCount(any())).thenReturn(0L);
        // 模拟数据库自增 ID 回填（Mockito 默认不会执行）
        when(userMapper.insert(any(User.class))).thenAnswer(invocation -> {
            ((User) invocation.getArgument(0)).setId(USER_ID);
            return 1;
        });

        authService.register(registerRequest("bob"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertEquals("bob", userCaptor.getValue().getUsername());
        // 注册成功后邀请凭证被标记为已使用，并记录注册用户
        ArgumentCaptor<InviteToken> inviteCaptor = ArgumentCaptor.forClass(InviteToken.class);
        verify(inviteTokenMapper).updateById(inviteCaptor.capture());
        assertEquals(1, inviteCaptor.getValue().getStatus());
        assertEquals(USER_ID, inviteCaptor.getValue().getUsedBy());
        assertNotNull(inviteCaptor.getValue().getUsedAt());
    }

    @Test
    void createInviteGeneratesValidToken() {
        authService.createInvite(USER_ID);

        ArgumentCaptor<InviteToken> captor = ArgumentCaptor.forClass(InviteToken.class);
        verify(inviteTokenMapper).insert(captor.capture());
        InviteToken invite = captor.getValue();
        assertNotNull(invite.getToken());
        assertEquals(48, invite.getToken().length());
        assertEquals(USER_ID, invite.getInvitedBy());
        assertEquals(0, invite.getStatus());
        // 有效期默认 7 天
        assertTrue(invite.getExpiresAt().isAfter(LocalDateTime.now().plusDays(6)));
        assertTrue(invite.getExpiresAt().isBefore(LocalDateTime.now().plusDays(8)));
    }

    // ---------- 修改密码 ----------

    @Test
    void changePasswordRejectsWrongOldPassword() {
        when(userMapper.selectById(USER_ID)).thenReturn(userWithPassword("old-pass"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword(USER_ID, request("wrong-old", "new-pass-1")));
        assertEquals("auth.password.incorrect", ex.getMessageKey());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changePasswordRejectsSameAsOld() {
        when(userMapper.selectById(USER_ID)).thenReturn(userWithPassword("old-pass"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.changePassword(USER_ID, request("old-pass", "old-pass")));
        assertEquals("auth.password.sameAsOld", ex.getMessageKey());
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void changePasswordUpdatesEncodedPasswordAndBumpsVersion() {
        when(userMapper.selectById(USER_ID)).thenReturn(userWithPassword("old-pass"));

        authService.changePassword(USER_ID, request("old-pass", "new-pass-1"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(captor.capture());
        assertTrue(passwordEncoder.matches("new-pass-1", captor.getValue().getPassword()));
        // 密码版本号递增，用于使修改前签发的旧 token 失效
        assertEquals(1, captor.getValue().getPasswordVersion());
    }
}
