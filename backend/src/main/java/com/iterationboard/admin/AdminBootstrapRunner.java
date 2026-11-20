package com.iterationboard.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 内置管理员启动初始化（1.1.0 裁定第一步，替代 install.bat 第 9 步的脚本建号）：
 * 应用启动、Flyway 迁移完成后执行（Spring Boot 在 ApplicationRunner 之前完成 Flyway 迁移）。
 *
 * - 库中无 login_name='admin' → 随机 20 位初始密码建号（platform_admin + 强制改密），
 *   密码写入 app.bootstrap-password-file 指向的密码文件，日志只打文件路径、绝不打密码；
 * - admin 已存在且为平台管理员 → 什么都不做（不写文件）；
 * - admin 登录名被普通账号占用 → 不改动，WARN + 审计 ADMIN_BOOTSTRAP_SKIPPED（事务服务内落行）。
 *
 * 任何异常只记 ERROR 不上抛：初始化失败不能拖垮服务启动（恢复路径是 reset-admin 运维命令）。
 * 开关 app.bootstrap-admin-enabled 默认开，仅供自动化测试等场景关闭启动建号。
 */
@Component
@Order(100)
@ConditionalOnProperty(name = "app.bootstrap-admin-enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrapRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final AdminBootstrapTxService txService;
    private final Path passwordFile;

    public AdminBootstrapRunner(AdminBootstrapTxService txService,
                                @Value("${app.bootstrap-password-file:config/admin-initial-password.txt}")
                                String passwordFile) {
        this.txService = txService;
        this.passwordFile = Path.of(passwordFile);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            AdminBootstrapTxService.Result result = txService.bootstrapAdmin();
            switch (result.outcome()) {
                case CREATED -> {
                    AdminPasswordSupport.writePasswordFile(passwordFile, result.generatedPassword());
                    log.info("初始管理员密码已写入 {}", passwordFile);
                }
                case SKIPPED_NAME_TAKEN -> log.warn(
                        "启动初始化跳过：登录名 admin 已被普通账号占用，未做改动；如需设置平台管理员请使用 reset-admin 运维命令");
                case ALREADY_ADMIN -> {
                    // 已初始化过，静默
                }
            }
        } catch (Exception e) {
            // 初始化失败只留痕，不让应用启动失败
            log.error("管理员启动初始化失败（不影响应用启动，可用 reset-admin 运维命令恢复）", e);
        }
    }
}
