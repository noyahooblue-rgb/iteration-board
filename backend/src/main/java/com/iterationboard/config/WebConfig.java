package com.iterationboard.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
}
