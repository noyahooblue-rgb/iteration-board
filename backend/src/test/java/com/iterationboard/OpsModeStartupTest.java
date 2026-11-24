package com.iterationboard;

import com.iterationboard.admin.ResetAdminOpsRunner;
import com.iterationboard.domain.AppUser;
import com.iterationboard.reminder.ScheduledJobs;
import com.iterationboard.repo.UserRepo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维命令模式（--ib.ops=...）启动装配回归测试（1.1.0）：
 * 复刻主类对 ops 模式的全部变换——WebApplicationType.NONE + ops profile +
 * spring.task.scheduling.enabled=false——真实启动一遍上下文。
 *
 * 背景：SecurityConfig.filterChain 无条件装配时需要 HttpSecurity bean，而 NONE 模式下
 * 该 bean 不存在，ops 进程在上下文初始化阶段即 APPLICATION FAILED TO START，
 * reset-admin.bat 在出厂包上不可用（2026-11-24 恶劣路径矩阵首跑 N2 实证，run 45351395587）。
 * 既有测试都在 Web 上下文里直调 runOps，装配层缺陷溜了过去；本类专守装配层。
 *
 * 不传 --ib.ops 参数启动：runner 在上下文刷新后才执行且成功会 System.exit 杀掉测试 JVM；
 * 装配失败发生在刷新阶段，与参数无关。启动后直调 runOps 验证 ops 服务路径在
 * NONE 上下文里端到端可用（等价于 runner 收到 reset-admin 后的实际工作）。
 * 全部账号为虚构测试数据；数据源与既有集成测试同一测试库。
 */
class OpsModeStartupTest {

    private static final String LOGIN = "opsbootuser";
    private static final Path PASSWORD_FILE = Path.of("target/test-ops-mode/admin-initial-password.txt");

    private static ConfigurableApplicationContext ctx;
    private static JdbcTemplate jdbc;
    private static PasswordEncoder encoder;

    @BeforeAll
    static void bootOpsContext() {
        SpringApplication app = new SpringApplication(IterationBoardApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("ops");
        app.setDefaultProperties(Map.of("spring.task.scheduling.enabled", "false"));
        // 覆盖项走命令行参数（最高优先级）：setDefaultProperties 会被 application.yml 的
        // 同名项盖掉（bootstrap 开关与密码文件路径在 yml 里都有定义），实测已踩过一次。
        ctx = app.run(
                "--app.bootstrap-admin-enabled=false",
                "--app.bootstrap-password-file=" + PASSWORD_FILE);
        jdbc = ctx.getBean(JdbcTemplate.class);
        encoder = ctx.getBean(PasswordEncoder.class);
        cleanUpRows();
    }

    @AfterAll
    static void closeAndClean() throws Exception {
        cleanUpRows();
        Files.deleteIfExists(PASSWORD_FILE);
        if (ctx != null) ctx.close();
    }

    private static void cleanUpRows() {
        jdbc.update("delete from audit_log where action = 'ADMIN_RESET_VIA_OPS'");
        jdbc.update("delete from app_user where login_name = ?", LOGIN);
    }

    @Test
    void opsContextStarts_withoutWebSecurityChain_butWithOpsBeans() {
        // 缺陷本体：NONE 模式上下文必须能完整启动（修复前在此处抛
        // UnsatisfiedDependencyException: filterChain 需要 HttpSecurity bean）。
        assertThat(ctx.isActive()).isTrue();
        // Web 过滤链不装配（ops 无 Web 容器、无请求可过滤）；
        // Web 模式下该 bean 存在性由 AdminEndpointSecurityTest 等 Web 测试守住。
        assertThat(ctx.getBeansOfType(SecurityFilterChain.class)).isEmpty();
        // ops 服务层依赖的安全 bean 照常在位。
        assertThat(ctx.getBean(PasswordEncoder.class)).isNotNull();
        assertThat(ctx.getBean(ResetAdminOpsRunner.class)).isNotNull();
        // 定时任务在 ops profile 下不装配（主类注释的双保险之一）。
        assertThat(ctx.getBeansOfType(ScheduledJobs.class)).isEmpty();
    }

    @Test
    void resetAdminRunOps_worksEndToEndInOpsContext() throws Exception {
        jdbc.update("insert into app_user (login_name, display_name, password_hash, avatar_color, "
                        + "platform_admin, status, must_change_password, failed_attempts, password_version, "
                        + "created_at, updated_at) values (?,?,?,?,?, 'ACTIVE', ?, 0, 0, now(), now())",
                LOGIN, "虚构用户" + LOGIN, encoder.encode("OldPass1234"), "blue", false, false);

        int code = ctx.getBean(ResetAdminOpsRunner.class).runOps(LOGIN);

        assertThat(code).isZero();
        AppUser after = ctx.getBean(UserRepo.class).findByLoginName(LOGIN).orElseThrow();
        assertThat(after.isPlatformAdmin()).isTrue();
        assertThat(after.isMustChangePassword()).isTrue();
        String written = Files.readString(PASSWORD_FILE);
        assertThat(written).contains("admin initial password:");
        // 落库实证（Gemini 终检补强）：密码文件里的临时密码必须与库中新哈希
        // 匹配（即真能用它登录），且审计行已写入——不只是文件存在。
        var pwMatcher = java.util.regex.Pattern.compile("admin initial password:\\s*(\\S+)").matcher(written);
        assertThat(pwMatcher.find()).isTrue();
        assertThat(encoder.matches(pwMatcher.group(1), after.getPasswordHash())).isTrue();
        Integer auditRows = jdbc.queryForObject(
                "select count(*) from audit_log where action = 'ADMIN_RESET_VIA_OPS'", Integer.class);
        assertThat(auditRows).isNotNull();
        assertThat(auditRows).isGreaterThanOrEqualTo(1);
    }
}
