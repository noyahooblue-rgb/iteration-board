package com.iterationboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
        // 缓存口径（C2 复核处置 SPA 3.1）：index.html 若被浏览器长缓存，发版后用户会
        // 拿着旧首页指向已不存在的旧哈希资源而白屏。故入口与一般静态回退走 no-cache
        // （每次校验，内容没变时 304 成本很低）；/assets/** 是 Vite 内容哈希命名文件，
        // 内容不变 URL 不变，可长缓存 + immutable。二者在同一 registry 内按模式
        // 具体程度分流（/assets/** 优先于 /**）。
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResourceResolver());
    }

    /**
     * 已知静态资源扩展名（C2 复核处置 SPA 3.3）：最后一段带点号时，只有扩展名在此
     * 白名单内才判为「静态文件缺失」回真 404；白名单外的点号段（如未来的
     * /report/2026.11 这类前端路由参数）仍回退 index.html，避免被「有扩展名即 404」
     * 的粗口径误杀。新增静态文件类型时在此补扩展名即可。
     */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "js", "mjs", "css", "map", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "cur",
            "woff", "woff2", "ttf", "otf", "eot", "json", "txt", "xml", "yaml", "yml", "pdf",
            "wasm", "mp4", "webm", "mp3", "wav", "zip");

    /**
     * 解析口径（顺序即优先级）：
     * 1. api / api/** —— 返回 null，保持后端真 404（JSON NOT_FOUND），不吞接口探测；
     *    判断精确到 path 等于 "api" 或以 "api/" 开头（"apiary" 等同前缀词不误伤）；
     * 2. classpath:/static/ 下真实存在且可读的资源 —— 原样返回（index.html、/assets/** 等）；
     * 3. 最后一段带已知静态扩展名（见 STATIC_EXTENSIONS）—— 返回 null，真 404，
     *    不把缺失的静态文件伪装成首页；带其他点号的段按前端路由处理；
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
                int dot = lastSegment.lastIndexOf('.');
                if (dot >= 0 && STATIC_EXTENSIONS.contains(
                        lastSegment.substring(dot + 1).toLowerCase(Locale.ROOT))) {
                    return null;
                }
            }
            Resource index = location.createRelative("index.html");
            return index.isReadable() ? index : null;
        }
    }
}
