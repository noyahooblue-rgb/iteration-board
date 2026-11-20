package com.iterationboard.config;

import com.iterationboard.repo.UserRepo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * P01 强制改密拦截：mustChangePassword=true 的账号（如安装生成的 admin、reset-admin 重置的账号）
 * 在改密完成前只能调用改密、查看本人信息与退出登录接口，其余一律 403 MUST_CHANGE_PASSWORD。
 */
@Component
public class MustChangePasswordInterceptor implements HandlerInterceptor {
    private static final Set<String> ALLOWED_SUFFIXES = Set.of(
            "/auth/change-password", "/auth/me", "/auth/logout", "/auth/notification-prefs",
            "/release-notes", "/release-notes/read", "/meta");

    private final UserRepo userRepo;

    public MustChangePasswordInterceptor(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/v1/")) return true;
        for (String suffix : ALLOWED_SUFFIXES) {
            if (path.endsWith(suffix)) return true;
        }
        boolean mustChange = userRepo.findByLoginName(auth.getName())
                .map(u -> u.isMustChangePassword())
                .orElse(false);
        if (!mustChange) return true;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"MUST_CHANGE_PASSWORD\",\"message\":\"首次登录必须先修改密码\",\"detail\":null}");
        return false;
    }
}
