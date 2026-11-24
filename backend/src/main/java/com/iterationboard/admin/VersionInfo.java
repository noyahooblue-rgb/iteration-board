package com.iterationboard.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 程序版本与构建日期的单一来源（1.1.0-rc2）：版本取 app.version（与 pom 一致），
 * 构建日期取 spring-boot build-info 生成的 BuildProperties（构建时间），缺失时如实返回「未知」。
 */
@Component
public class VersionInfo {
    private final String version;
    private final BuildProperties buildProperties;

    public VersionInfo(@Value("${app.version:1.1.0}") String version,
                       Optional<BuildProperties> buildProperties) {
        this.version = version;
        this.buildProperties = buildProperties.orElse(null);
    }

    public String version() {
        if (buildProperties != null && buildProperties.getVersion() != null) return buildProperties.getVersion();
        return version;
    }

    public String buildDate() {
        if (buildProperties != null && buildProperties.getTime() != null) return buildProperties.getTime().toString();
        return "未知";
    }
}
