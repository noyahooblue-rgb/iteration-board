package com.iterationboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final PasswordVersionInterceptor interceptor;
    private final MustChangePasswordInterceptor mustChangePasswordInterceptor;

    public WebConfig(PasswordVersionInterceptor interceptor,
                     MustChangePasswordInterceptor mustChangePasswordInterceptor) {
        this.interceptor = interceptor;
        this.mustChangePasswordInterceptor = mustChangePasswordInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(mustChangePasswordInterceptor).addPathPatterns("/api/v1/**");
    }

    /**
     * SPA 兜底（rc4 方案 A，取代原 SpaController 的显式路由清单）：
     * 前端新增路由（如 /i/:id、/change-password）不再需要后端同步补清单——
     * 任意无扩展名的非 API 路径都回退 index.html，交给前端路由处理。
     * 注册 "/**" 会顶掉 Spring Boot 自带的同模式静态资源处理，故静态文件
     * 存在性判断在本解析器内一并完成（见 SpaFallbackResourceResolver）。
     * 生产 jar 的 index.html 与前端构建产物由打包阶段嵌入 BOOT-INF/classes/static/。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 顺序口径：控制器映射 order=0、Boot 欢迎页映射（WelcomePageHandlerMapping）order=2。
        // 资源映射默认 order=LOWEST_PRECEDENCE-1 会排在欢迎页之后，"/" 会被欢迎页抢走做
        // forward:index.html（真实容器靠 forward 兜底、MockMvc 下则是空响应），故显式设为 1：
        // 仍在控制器之后（/api/** 与 RootIndexController 的 "/" 直出不受影响），但在欢迎页之前，
        // 其余路径一律由本解析器裁决，不走欢迎页 forward。
        registry.setOrder(1);
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaFallbackResourceResolver());
    }

    /**
     * 解析口径（顺序即优先级）：
     * 1. api / api/** —— 返回 null，保持后端真 404（JSON NOT_FOUND），不吞接口探测；
     * 2. classpath:/static/ 下真实存在且可读的资源 —— 原样返回（index.html、/assets/** 等）；
     * 3. 最后一段带 "."（扩展名）—— 返回 null，真 404，不把缺失的静态文件伪装成首页；
     * 4. 其余 —— 返回 index.html；index.html 本身不存在时返回 null。
     * 注：根路径 "/" 的请求路径为空串，会被 ResourceHttpRequestHandler 在解析器链之前
     * 短路（见 RootIndexController 的说明），到不了本解析器，由该控制器单独直出。
     */
    static final class SpaFallbackResourceResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            String path = resourcePath == null ? "" : resourcePath;
            if (path.startsWith("/")) path = path.substring(1);
            if (path.equals("api") || path.startsWith("api/")) {
                return null;
            }
            if (!path.isEmpty()) {
                Resource existing = location.createRelative(path);
                if (existing.isReadable() && checkResource(existing, location)) {
                    return existing;
                }
                String lastSegment = path.substring(path.lastIndexOf('/') + 1);
                if (lastSegment.contains(".")) {
                    return null;
                }
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }
}
