package com.protask.service;

import cn.dev33.satoken.stp.SaLoginModel;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.protask.common.BusinessException;
import com.protask.common.PasswordStrengthValidator;
import com.protask.common.ResultCode;
import com.protask.dto.ChangePasswordRequest;
import com.protask.dto.InviteVO;
import com.protask.dto.LoginRequest;
import com.protask.dto.LoginResponse;
import com.protask.dto.RegisterRequest;
import com.protask.dto.UserVO;
import com.protask.entity.InviteToken;
import com.protask.entity.User;
import com.protask.mapper.InviteTokenMapper;
import com.protask.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 认证服务：注册、登录、登出、当前用户。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final InviteTokenMapper inviteTokenMapper;
    private final PasswordEncoder passwordEncoder;

    /** 邀请链接有效期（天） */
    private static final int INVITE_VALID_DAYS = 7;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * 用户注册（需携带有效的邀请链接凭证）。
     */
    public UserVO register(RegisterRequest request) {
        // 公开注册已关闭：仅持有效邀请链接的用户可注册，注册成功后凭证作废
        InviteToken invite = validateInvite(request.getInviteToken());
        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.CONFLICT, "auth.username.exists");
        }
        PasswordStrengthValidator.validate(request.getPassword());
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getNickname() != null ? request.getNickname() : request.getUsername());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEmail(request.getEmail());
        user.setRole("user");
        user.setStatus(1);
        userMapper.insert(user);
        // 标记邀请凭证已使用，防止同一链接重复注册
        invite.setStatus(1);
        invite.setUsedAt(LocalDateTime.now());
        invite.setUsedBy(user.getId());
        inviteTokenMapper.updateById(invite);
        return toVO(user);
    }

    /**
     * 生成一次性注册邀请链接（登录用户可用），有效期 {@value #INVITE_VALID_DAYS} 天。
     */
    public InviteVO createInvite(Long userId) {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        InviteToken invite = new InviteToken();
        invite.setToken(HexFormat.of().formatHex(bytes));
        invite.setInvitedBy(userId);
        invite.setStatus(0);
        invite.setExpiresAt(LocalDateTime.now().plusDays(INVITE_VALID_DAYS));
        inviteTokenMapper.insert(invite);
        return toInviteVO(invite);
    }

    /**
     * 校验邀请链接凭证：存在、未使用且未过期，返回有效凭证；否则抛出对应错误。
     */
    public InviteToken validateInvite(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.invite.required");
        }
        InviteToken invite = inviteTokenMapper.selectOne(
                new LambdaQueryWrapper<InviteToken>().eq(InviteToken::getToken, token));
        if (invite == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.invite.invalid");
        }
        if (invite.getStatus() != null && invite.getStatus() == 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.invite.used");
        }
        if (invite.getExpiresAt() == null || invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.invite.expired");
        }
        return invite;
    }

    /** 组装邀请视图：inviteUrl 基于请求 Origin 拼接，缺失时回退相对路径 */
    public InviteVO toInviteVO(InviteToken invite) {
        InviteVO vo = new InviteVO();
        vo.setToken(invite.getToken());
        vo.setExpiresAt(invite.getExpiresAt());
        String origin = null;
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            origin = attrs.getRequest().getHeader("Origin");
        }
        vo.setInviteUrl((origin != null ? origin : "") + "/register?invite=" + invite.getToken());
        return vo;
    }

    /**
     * 用户登录，成功后签发 JWT token。
     */
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "auth.credentials.invalid");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "auth.account.disabled");
        }
        // 密码版本号写入 token，供拦截器对比：修改/重置密码后旧 token 立即失效
        StpUtil.login(user.getId(), new SaLoginModel()
                .setExtra("pwdVersion", passwordVersion(user)));
        return new LoginResponse(StpUtil.getTokenValue(), toVO(user));
    }

    /**
     * 登出当前会话。
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 获取当前登录用户信息。
     */
    public UserVO currentUser() {
        User user = userMapper.selectById(StpUtil.getLoginIdAsLong());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return toVO(user);
    }

    /**
     * 修改当前用户密码：校验旧密码后更新，并递增密码版本号使其余会话的旧 token 立即失效。
     */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.password.incorrect");
        }
        if (request.getOldPassword().equals(request.getNewPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "auth.password.sameAsOld");
        }
        PasswordStrengthValidator.validate(request.getNewPassword());
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordVersion(passwordVersion(user) + 1);
        userMapper.updateById(user);
    }

    /** 密码版本号（历史数据为 null 时按 0 处理） */
    private static int passwordVersion(User user) {
        return user.getPasswordVersion() != null ? user.getPasswordVersion() : 0;
    }

    public static UserVO toVO(User user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo);
        vo.setFullName(UserVO.fullNameOf(user.getFirstName(), user.getLastName()));
        return vo;
    }
}
