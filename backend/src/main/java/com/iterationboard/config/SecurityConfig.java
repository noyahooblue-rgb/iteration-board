package com.iterationboard.config;

import tools.jackson.databind.ObjectMapper;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话 Cookie 认证 + CSRF。
 * CSRF（已于 M1 收尾正式写入 contract/openapi.yaml 的安全约定）：启用 Spring Security CSRF，
 * 令牌经 Cookie 下发（Cookie 名 XSRF-TOKEN，非 HttpOnly 供前端读取），
 * 前端写请求须带请求头 X-XSRF-TOKEN 提交该值。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepo userRepo) {
        return loginName -> {
            AppUser u = userRepo.findByLoginName(loginName)
                    .orElseThrow(() -> new UsernameNotFoundException(loginName));
            return User.withUsername(u.getLoginName())
                    .password(u.getPasswordHash())
                    .authorities(u.isPlatformAdmin() ? new String[]{"ROLE_USER", "ROLE_PLATFORM_ADMIN"} : new String[]{"ROLE_USER"})
                    .build();
        };
    }

    /**
     * 平台管理员授权（按库中当前标记判定，不用登录时的会话权限快照）：
     * 授予/撤销平台管理员对已登录会话立即生效——与各管理接口内 requirePlatformAdmin 的
     * 数据库校验同口径。若用 hasRole（会话快照），被撤销者的旧会话会残留管理员权限、
     * 新授予者又要重登才生效，两头都不对。
     */
    static AuthorizationManager<RequestAuthorizationContext> platformAdminFromDb(UserRepo userRepo) {
        return (authSupplier, ctx) -> {
            var auth = authSupplier.get();
            if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(String.valueOf(auth.getPrincipal()))) {
                return new AuthorizationDecision(false);
            }
            boolean admin = userRepo.findByLoginName(auth.getName())
                    .map(u -> u.isPlatformAdmin() && "ACTIVE".equals(u.getStatus()) && u.getDeletedAt() == null)
                    .orElse(false);
            return new AuthorizationDecision(admin);
        };
    }

    /**
     * 仅 Web（SERVLET）模式装配：运维命令模式（--ib.ops=...，WebApplicationType.NONE）下没有
     * HttpSecurity bean，无条件装配会让整个上下文启动失败（1.1.0 恶劣路径矩阵首跑实证：
     * reset-admin 以 ops profile 启动出厂 jar 时 APPLICATION FAILED TO START）。
     * ops 模式无 Web 容器、无请求可过滤，不需要过滤链；密码编码、用户详情等其余安全 bean
     * 不带此条件，ops 服务层照常使用。Web 模式下条件恒成立，本 bean 的装配与行为不变。
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper, UserRepo userRepo) throws Exception {
        http
            // SPA 口径（契约 0.2.0）：前端把 XSRF-TOKEN Cookie 原值放进 X-XSRF-TOKEN 请求头。
            // 必须配 plain 的 CsrfTokenRequestAttributeHandler——Spring Security 默认的 XOR 掩码处理器
            // 不接受 Cookie 原值，会导致浏览器端一切写请求 403（M3 jar 端到端冒烟实测发现，见 TEST-REPORT）。
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            // 令牌物化：延迟令牌默认只在校验写请求时才生成下发，首访浏览器在首个 POST 前拿不到
            // XSRF-TOKEN Cookie，第一次登录/注册必然 403。本过滤器在每个请求里主动读取令牌，
            // 迫使仓库在安全请求（如 GET /auth/captcha）的响应里就把 Cookie 下发（官方 SPA 配方）。
            .addFilterAfter(new CsrfTokenMaterializationFilter(), CsrfFilter.class)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .authorizeHttpRequests(auth -> auth
                    // 免登录白名单（严格四项，新增须先改 AdminEndpointSecurityTest 的白名单断言——相当于强制评审）。
                    // 1.1.0-rc2 安全裁定（hsa43amn）：原 /api/v1/admin/bootstrap、bootstrap/status、reset-admin
                    // 三个免登录管理员接口已删除（建号走 AdminBootstrapRunner、重置走 --ib.ops=reset-admin 运维命令）。
                    .requestMatchers("/api/v1/meta", "/api/v1/auth/captcha", "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                    // 平台管理接口一律要求平台管理员（按库判定，见 platformAdminFromDb；接口内另有二次校验）。
                    .requestMatchers("/api/v1/admin/**").access(platformAdminFromDb(userRepo))
                    // 接口文档默认关闭（springdoc 开关，见 application.yml）；开启时也仅平台管理员可访问。
                    .requestMatchers("/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html", "/openapi.yaml").access(platformAdminFromDb(userRepo))
                    // 1.0.3 修复（实测第四轮）：只对 /api/** 要求认证；/api/** 以外是
                    // 前端静态资源与 SPA 路由，全部放行（否则未登录打开首页直接 401
                    // JSON，永远看不到登录页）。SPA 路由的 index.html 回退见 WebConfig 的 SPA 兜底资源解析器。
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll())
            .exceptionHandling(ex -> ex
                    // 401/403 的 JSON 只用于 /api/**；非 API 路径走容器默认状态码。
                    // 字符集必须显式 UTF-8：getWriter() 默认按 ISO-8859-1 输出，
                    // 中文消息会变问号（实测第四轮缺陷 2）。
                    .authenticationEntryPoint((req, res, e) -> {
                        if (req.getRequestURI().startsWith("/api/")) {
                            res.setStatus(HttpStatus.UNAUTHORIZED.value());
                            res.setCharacterEncoding("UTF-8");
                            res.setContentType("application/json;charset=UTF-8");
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("code", "UNAUTHORIZED");
                            m.put("message", "未登录或会话已过期");
                            m.put("detail", null);
                            res.getWriter().write(objectMapper.writeValueAsString(m));
                        } else {
                            res.sendError(HttpStatus.UNAUTHORIZED.value());
                        }
                    })
                    .accessDeniedHandler((req, res, e) -> {
                        if (req.getRequestURI().startsWith("/api/")) {
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setCharacterEncoding("UTF-8");
                            res.setContentType("application/json;charset=UTF-8");
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("code", "FORBIDDEN");
                            m.put("message", "无权限执行此操作");
                            m.put("detail", null);
                            res.getWriter().write(objectMapper.writeValueAsString(m));
                        } else {
                            res.sendError(HttpStatus.FORBIDDEN.value());
                        }
                    }))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable()); // 登出由 AuthController 处理（需失效会话并返回 204）
        return http.build();
    }

    /** 主动读取请求属性中的 CsrfToken，迫使延迟令牌生成并经 Cookie 下发（见 filterChain 注释）。 */
    static final class CsrfTokenMaterializationFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) token.getToken();
            filterChain.doFilter(request, response);
        }
    }
}
