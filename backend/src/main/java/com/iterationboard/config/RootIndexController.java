package com.iterationboard.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 根路径 "/" 的 index.html 直出（rc4 方案 A 的补位，仅此一条路由）：
 * Spring Framework 7 的 ResourceHttpRequestHandler 对空路径在进入解析器链之前就
 * 短路为 404（ResourceHandlerUtils.shouldIgnoreInputPath 对空串返回 true，
 * 实测解析器对 "/" 请求从未被调用、直接 NoResourceFoundException），
 * 故根路径无法由 WebConfig 的 SPA 兜底解析器处理，单独由此控制器直出；
 * 其余全部前端路由走 WebConfig 的通用兜底，不再维护显式路由清单（原 SpaController 已删）。
 */
@Controller
public class RootIndexController {

    @GetMapping("/")
    public ResponseEntity<Resource> index() {
        ClassPathResource res = new ClassPathResource("static/index.html");
        if (!res.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(res);
    }
}
