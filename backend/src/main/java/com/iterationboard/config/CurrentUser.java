package com.iterationboard.config;

import com.iterationboard.common.ApiException;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 从会话安全上下文解析当前登录用户。 */
@Component
public class CurrentUser {
    private final UserRepo userRepo;

    public CurrentUser(UserRepo userRepo) {
        this.userRepo = userRepo;
    }

    public AppUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "未登录或会话已过期");
        }
        return userRepo.findByLoginName(auth.getName())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "账号不存在"));
    }
}
