package com.iterationboard.auth;

import com.iterationboard.common.ApiException;
import com.iterationboard.config.PasswordVersionInterceptor;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String[] AVATAR_COLORS = {"blue", "indigo", "violet", "pink", "orange", "green", "teal"};

    private final UserRepo userRepo;
    private final PasswordEncoder encoder;
    private final CaptchaService captchaService;
    private final UserDetailsService userDetailsService;
    private final SecurityContextRepository contextRepository;
    private final boolean registrationOpen;
    private final String bootstrapPasswordFile;

    public AuthService(UserRepo userRepo, PasswordEncoder encoder, CaptchaService captchaService,
                       UserDetailsService userDetailsService, SecurityContextRepository contextRepository,
                       @Value("${app.registration-open:true}") boolean registrationOpen,
                       @Value("${app.bootstrap-password-file:config/admin-initial-password.txt}")
                       String bootstrapPasswordFile) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.captchaService = captchaService;
        this.userDetailsService = userDetailsService;
        this.contextRepository = contextRepository;
        this.registrationOpen = registrationOpen;
        this.bootstrapPasswordFile = bootstrapPasswordFile;
    }

    /** 委托 PasswordPolicy（修订六契约 1）：无 loginName 时只校验长度/三类。 */
    public static void validatePassword(String password) {
        PasswordPolicy.validate(password);
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
        PasswordPolicy.validate(password, loginName);
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
        u.setLastLoginAt(now);
        // 修订六 §4 老账号平滑过渡：明文不合规（旧规则弱密码）照常登录，但置强制改密
        if (!PasswordPolicy.complies(password, u.getLoginName()) && !u.isMustChangePassword()) {
            u.setMustChangePassword(true);
        }
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
        PasswordPolicy.validate(newPassword, u.getLoginName());
        // 改密前是否处于「强制改密」状态：是 → 本次是初始密码的首次改密，成功后删除初始密码文件
        boolean wasMustChangePassword = u.isMustChangePassword();
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        u.setPasswordVersion(u.getPasswordVersion() + 1);
        userRepo.save(u);
        // 刷新当前会话的版本标记；其他会话下次请求时被拦截器失效（见 PasswordVersionInterceptor）
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.setAttribute(PasswordVersionInterceptor.SESSION_ATTR, u.getPasswordVersion());
        }
        if (wasMustChangePassword) {
            deleteBootstrapPasswordFileQuietly();
        }
    }

    /** 删除初始密码文件（1.1.0 裁定第一步：不再靠 start.bat 删）。best effort：失败只记 WARN，不影响改密结果。 */
    private void deleteBootstrapPasswordFileQuietly() {
        try {
            if (Files.deleteIfExists(Path.of(bootstrapPasswordFile))) {
                log.info("初始密码文件已删除（首次改密完成）：{}", bootstrapPasswordFile);
            }
        } catch (Exception e) {
            log.warn("删除初始密码文件失败（路径 {}），请手动删除：{}", bootstrapPasswordFile, e.toString());
        }
    }
}
