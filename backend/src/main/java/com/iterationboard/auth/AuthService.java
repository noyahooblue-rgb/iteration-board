package com.iterationboard.auth;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.PasswordVersionInterceptor;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class AuthService {
    private static final String[] AVATAR_COLORS = {"blue", "indigo", "violet", "pink", "orange", "green", "teal"};

    private final UserRepo userRepo;
    private final PasswordEncoder encoder;
    private final CaptchaService captchaService;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository contextRepository;
    private final boolean registrationOpen;

    public AuthService(UserRepo userRepo, PasswordEncoder encoder, CaptchaService captchaService,
                       UserDetailsService userDetailsService, SecurityContextRepository contextRepository,
                       @Value("${app.registration-open:true}") boolean registrationOpen) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.captchaService = captchaService;
        this.userDetailsService = userDetailsService;
        this.contextRepository = contextRepository;
        this.registrationOpen = registrationOpen;
    }

    public static void validatePassword(String password) {
        if (password == null || password.length() < 8
                || !password.chars().anyMatch(Character::isLetter)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw ApiException.validation("密码至少 8 位且同时含字母和数字");
        }
    }

    @Transactional
    public AppUser register(String loginName, String displayName, String password,
                            String captchaId, String captchaCode,
                            HttpServletRequest request, HttpServletResponse response) {
        if (!registrationOpen) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REGISTRATION_CLOSED", "当前已关闭开放注册");
        }
        if (loginName == null || loginName.length() < 3 || loginName.length() > 32) {
            throw ApiException.validation("登录名长度须为 3 至 32 位");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 32) {
            throw ApiException.validation("显示姓名不能为空且不超过 32 位");
        }
        validatePassword(password);
        HttpSession session = request.getSession(true);
        if (!captchaService.verify(session, captchaId, captchaCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID", "验证码不正确");
        }
        if (userRepo.existsByLoginName(loginName)) {
            throw new ApiException(HttpStatus.CONFLICT, "LOGIN_NAME_TAKEN", "登录名已被占用");
        }
        AppUser u = new AppUser();
        u.setLoginName(loginName);
        u.setDisplayName(displayName);
        u.setPasswordHash(encoder.encode(password));
        u.setAvatarColor(AVATAR_COLORS[Math.floorMod(loginName.hashCode(), AVATAR_COLORS.length)]);
        // 自助注册永不授予平台管理员；平台管理员由安装引导创建的 admin
        // 或由已有管理员在平台管理中授予。
        u.setPlatformAdmin(false);
        userRepo.save(u);
        establishSession(u, request, response);
        return u;
    }

    @Transactional
    public AppUser login(String loginName, String password, String captchaId, String captchaCode,
                         HttpServletRequest request, HttpServletResponse response) {
        AppUser u = userRepo.findByLoginName(loginName).orElse(null);
        if (u == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录名或密码不正确");
        }
        if (!"ACTIVE".equals(u.getStatus()) || u.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "账号已停用，请联系平台管理员");
        }
        Instant now = Instant.now();
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)) {
            throw locked(u);
        }
        // 连续失败 3 次后必须验证码
        if (u.getFailedAttempts() >= 3) {
            HttpSession session = request.getSession(true);
            if (captchaId == null || captchaCode == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_REQUIRED", "失败次数过多，请输入图形验证码");
            }
            if (!captchaService.verify(session, captchaId, captchaCode)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CAPTCHA_INVALID", "验证码不正确");
            }
        }
        if (!encoder.matches(password == null ? "" : password, u.getPasswordHash())) {
            int attempts = u.getFailedAttempts() + 1;
            u.setFailedAttempts(attempts);
            if (attempts >= 5) {
                u.setLockedUntil(now.plus(15, ChronoUnit.MINUTES));
                userRepo.save(u);
                throw locked(u);
            }
            userRepo.save(u);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "登录名或密码不正确");
        }
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        userRepo.save(u);
        establishSession(u, request, response);
        return u;
    }

    private ApiException locked(AppUser u) {
        return new ApiException(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "失败次数过多，账号已锁定 15 分钟",
                Map.of("lockedUntil", String.valueOf(u.getLockedUntil())));
    }

    private void establishSession(AppUser u, HttpServletRequest request, HttpServletResponse response) {
        UserDetails details = userDetailsService.loadUserByUsername(u.getLoginName());
        var auth = new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(PasswordVersionInterceptor.SESSION_ATTR, u.getPasswordVersion());
    }

    @Transactional
    public void changePassword(AppUser u, String oldPassword, String newPassword, HttpServletRequest request) {
        if (!encoder.matches(oldPassword == null ? "" : oldPassword, u.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_OLD_PASSWORD", "旧密码不正确");
        }
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        u.setPasswordVersion(u.getPasswordVersion() + 1);
        userRepo.save(u);
        // 刷新当前会话的版本标记；其他会话下次请求时被拦截器失效（见 PasswordVersionInterceptor）
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(PasswordVersionInterceptor.SESSION_ATTR, u.getPasswordVersion());
        }
    }
}
