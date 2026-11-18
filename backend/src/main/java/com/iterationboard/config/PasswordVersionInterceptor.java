package com.iterationboard.config;

import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 改密后使其他会话失效的实现：登录时把用户 passwordVersion 写入会话；
 * 每次请求比对会话值与数据库当前值，不一致则销毁会话并返回 401。
 * 当前会话在改密成功后会同步刷新该值，因此不受影响。
 */
@Component
public class PasswordVersionInterceptor implements HandlerInterceptor {
    public static final String SESSION_ATTR = "passwordVersion";
    private final UserRepo userRepo;

    public PasswordVersionInterceptor(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null) return true;
        Object v = session.getAttribute(SESSION_ATTR);
        if (v == null) return true;
        AppUser u = userRepo.findByLoginName(auth.getName()).orElse(null);
        if (u != null && ((Number) v).intValue() != u.getPasswordVersion()) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"密码已修改，会话失效，请重新登录\",\"detail\":null}");
            return false;
        }
        return true;
    }
}
