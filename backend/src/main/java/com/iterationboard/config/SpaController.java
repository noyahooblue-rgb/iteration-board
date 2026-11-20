package com.iterationboard.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 回退（1.0.3，实测第四轮修复）：前端路由（/board、/workbench 等）不是
 * 真实静态文件，直接访问或刷新时由 jar 返回 index.html，交给前端路由处理。
 *
 * 实现口径：显式列出前端路由（与 frontend/src/router/index.ts 保持一致，
 * 新增前端路由时同步补一行），逐个返回 classpath:/static/index.html，
 * 内容类型显式 text/html。用显式清单而不是 /** 通配回退，是为了不吞掉
 * 静态资源（/assets/**、/favicon.ico 等带扩展名的真实文件仍由静态资源
 * 处理器按文件提供）与未知 API 路径（仍走 /api/** 的认证与 404 口径）。
 * 生产 jar 的 index.html 由打包阶段把前端构建产物嵌入 BOOT-INF/classes/static/。
 */
@Controller
public class SpaController {

    @GetMapping(value = {
            "/",
            "/login",
            "/showcase",
            "/workbench",
            "/board",
            "/pool",
            "/planning",
            "/iterations",
            "/iterations/{id}",
            "/standup",
            "/overview",
            "/qa",
            "/reports",
            "/backlog",
            "/search",
            "/trash",
            "/settings",
            "/me/settings",
            "/admin",
            "/teams/new",
            "/teams/pending",
            "/task/{id}",
            "/placeholder/{name}"
    })
    public ResponseEntity<Resource> index() {
        ClassPathResource res = new ClassPathResource("static/index.html");
        if (!res.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(res);
    }
}
