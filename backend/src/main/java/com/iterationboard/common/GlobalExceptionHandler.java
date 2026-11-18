package com.iterationboard.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, Object detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("message", message);
        m.put("detail", detail);
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage(), e.detail());
    }

    /** 未知路径应答 404 而非落入兜底 500（修订二全面复查发现：探测不存在的接口曾返回 500）。 */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(org.springframework.web.servlet.resource.NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst().map(f -> f.getField() + ": " + f.getDefaultMessage()).orElse("参数校验失败");
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", msg, null);
    }

    /** 代号唯一索引（V2）等唯一约束的并发竞态兜底：服务层已先行校验，此处仅处理两请求同时穿过校验的情形。 */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(org.springframework.dao.DataIntegrityViolationException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains("uk_team_code_lower")) {
            return body(HttpStatus.CONFLICT, "TEAM_CODE_TAKEN", "小组代号已被占用（不区分大小写），请换一个代号", null);
        }
        log.warn("数据完整性冲突", e);
        return body(HttpStatus.CONFLICT, "CONFLICT", "数据冲突，请刷新后重试", null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> optimistic(OptimisticLockingFailureException e) {
        return body(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已被他人修改，请刷新后重试", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        log.error("未处理异常", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "服务器内部错误", null);
    }
}
