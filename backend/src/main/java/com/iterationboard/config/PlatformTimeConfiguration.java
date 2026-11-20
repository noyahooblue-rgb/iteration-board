package com.iterationboard.config;

import com.iterationboard.common.PlatformTime;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * 平台时区与 JVM 默认时区对齐（1.0.4 时区统一）：
 * Spring 上下文初始化时把 JVM 默认时区设为平台时区（app.timezone，默认 Asia/Shanghai），
 * 保证任何遗漏的无参 now()/systemDefault() 调用也按平台口径计算。
 * 主类 main() 在 Spring 启动前已先行设置一次（兜住启动期代码），此处为上下文内的正式对齐点。
 */
@Configuration
public class PlatformTimeConfiguration {

    private final PlatformTime platformTime;

    public PlatformTimeConfiguration(PlatformTime platformTime) {
        this.platformTime = platformTime;
    }

    @PostConstruct
    void alignJvmDefaultTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(platformTime.zone()));
    }
}
