package com.iterationboard;

import com.iterationboard.common.PlatformTime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class IterationBoardApplication {
    public static void main(String[] args) {
        // 时区统一（1.0.4）：Spring 上下文起来之前先把 JVM 默认时区设为平台时区
        // （环境变量 APP_TIMEZONE，与 application.yml 的 app.timezone 同源；默认 Asia/Shanghai），
        // 保证启动期与任何遗漏的无参 now() 调用都按平台口径；PlatformTime Bean 初始化时会再对齐一次。
        String tz = System.getenv().getOrDefault("APP_TIMEZONE", PlatformTime.DEFAULT_ZONE_ID);
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(tz)));
        SpringApplication.run(IterationBoardApplication.class, args);
    }
}
