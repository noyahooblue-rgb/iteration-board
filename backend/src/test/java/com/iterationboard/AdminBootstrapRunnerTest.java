package com.iterationboard;

import com.iterationboard.admin.AdminBootstrapRunner;
import com.iterationboard.admin.ResetAdminOpsRunner;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.UserRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 1.1.0 裁定第一步（业务初始化收进应用）的集成测试：
 * AdminBootstrapRunner 启动建号 / reset-admin 运维命令 / 改密删密码文件。
 *
 * 本类以独立上下文开启启动建号（共享测试上下文默认关闭，见 src/test/resources/application.properties），
 * 且不用类级 @Transactional（runner/ops 自带事务、并发用例需要真实多事务），
 * 改为每个用例前后按登录名与审计动作精确清理自己造的数据。全部账号为虚构测试数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.bootstrap-admin-enabled=true",
        "app.bootstrap-password-file=target/test-bootstrap/admin-initial-password.txt"
})
class AdminBootstrapRunnerTest {

    private static final Pattern PASSWORD_LINE = Pattern.compile("admin initial password:\\s*(\\S+)");
    private static final List<String> TEST_LOGINS = List.of("admin", "firstboss", "plainmate", "opsuser", "ghostuser");

    @Autowired AdminBootstrapRunner bootstrapRunner;
    @Autowired ResetAdminOpsRunner resetOpsRunner;
    @Autowired UserRepo userRepo;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Value("${app.bootstrap-password-file}")
    String passwordFileValue;

    private Path passwordFile;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        passwordFile = Path.of(passwordFileValue);
        jdbc.update("delete from audit_log where action in "
                + "('ADMIN_BOOTSTRAP','ADMIN_BOOTSTRAP_SKIPPED','ADMIN_RESET_VIA_OPS')");
        jdbc.update("delete from app_user where login_name in ('admin','firstboss','plainmate','opsuser','ghostuser')");
        Files.deleteIfExists(passwordFile);
    }

    // ---------- helpers ----------

    private void insertUser(String login, String rawPassword, boolean platformAdmin, boolean mustChange) {
        jdbc.update("insert into app_user (login_name, display_name, password_hash, avatar_color, "
                        + "platform_admin, status, must_change_password, failed_attempts, password_version, "
                        + "created_at, updated_at) values (?,?,?,?,?, 'ACTIVE', ?, 0, 0, now(), now())",
                login, "虚构用户" + login, encoder.encode(rawPassword), "blue", platformAdmin, mustChange);
    }

    private String passwordFromFile() throws Exception {
        assertThat(passwordFile).exists();
        String content = Files.readString(passwordFile);
        Matcher m = PASSWORD_LINE.matcher(content);
        assertThat(m.find()).as("密码文件第一行格式须可被 'admin initial password:' 正则解析").isTrue();
        return m.group(1);
    }

    private long auditCount(String action) {
        Long n = jdbc.queryForObject("select count(*) from audit_log where action = ?", Long.class, action);
        return n == null ? 0 : n;
    }

    // ---------- 启动初始化 ----------

    @Test
    void firstStartup_createsAdmin_writesPasswordFile_audits() throws Exception {
        bootstrapRunner.run(null);

        AppUser admin = userRepo.findByLoginName("admin").orElseThrow();
        assertThat(admin.isPlatformAdmin()).isTrue();
        assertThat(admin.isMustChangePassword()).isTrue();
        assertThat(admin.getStatus()).isEqualTo("ACTIVE");
        assertThat(admin.getDisplayName()).isEqualTo("平台管理员");

        String password = passwordFromFile();
        // 密码规格：20 位、三类齐全、无易混字符
        assertThat(password).hasSize(20);
        assertThat(password.chars().anyMatch(Character::isUpperCase)).isTrue();
        assertThat(password.chars().anyMatch(Character::isLowerCase)).isTrue();
        assertThat(password.chars().anyMatch(Character::isDigit)).isTrue();
        assertThat(password).doesNotContainPattern("[0O1lI]");
        // 文件中的密码与库中哈希对应；第二行与约定文案逐字一致
        assertThat(encoder.matches(password, admin.getPasswordHash())).isTrue();
        List<String> lines = Files.readAllLines(passwordFile);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).isEqualTo(
                "Created by the application. This file is deleted automatically after the admin password is changed.");

        assertThat(auditCount("ADMIN_BOOTSTRAP")).isEqualTo(1);
    }

    @Test
    void secondStartup_doesNotRecreateOrRewriteFile() throws Exception {
        bootstrapRunner.run(null);
        AppUser first = userRepo.findByLoginName("admin").orElseThrow();
        String firstContent = Files.readString(passwordFile);

        bootstrapRunner.run(null);

        AppUser second = userRepo.findByLoginName("admin").orElseThrow();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getPasswordHash()).isEqualTo(first.getPasswordHash());
        assertThat(Files.readString(passwordFile)).isEqualTo(firstContent);
        assertThat(auditCount("ADMIN_BOOTSTRAP")).isEqualTo(1);
    }

    @Test
    void concurrentStartups_createSingleAdmin() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    bootstrapRunner.run(null);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        Long admins = jdbc.queryForObject(
                "select count(*) from app_user where login_name = 'admin'", Long.class);
        assertThat(admins).isEqualTo(1);
        assertThat(auditCount("ADMIN_BOOTSTRAP")).isEqualTo(1);
        // 文件中的密码与最终哈希对应（只有一个创建者写文件）
        AppUser admin = userRepo.findByLoginName("admin").orElseThrow();
        assertThat(encoder.matches(passwordFromFile(), admin.getPasswordHash())).isTrue();
    }

    @Test
    void sameNameOrdinaryAccount_leftUntouched_skippedAudit_noFile() {
        insertUser("admin", "Ordinary123", false, false);
        String hashBefore = userRepo.findByLoginName("admin").orElseThrow().getPasswordHash();

        bootstrapRunner.run(null);

        AppUser after = userRepo.findByLoginName("admin").orElseThrow();
        assertThat(after.isPlatformAdmin()).isFalse();
        assertThat(after.isMustChangePassword()).isFalse();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
        assertThat(passwordFile).doesNotExist();
        assertThat(auditCount("ADMIN_BOOTSTRAP_SKIPPED")).isEqualTo(1);
        assertThat(auditCount("ADMIN_BOOTSTRAP")).isZero();
    }

    @Test
    void upgradeScenario_existingAdminKept_adminCreated() throws Exception {
        // 升级前既有数据：第一个注册者是平台管理员（登录名非 admin）+ 若干普通用户
        insertUser("firstboss", "BossPass123", true, false);
        insertUser("plainmate", "MatePass123", false, false);
        String bossHash = userRepo.findByLoginName("firstboss").orElseThrow().getPasswordHash();
        String mateHash = userRepo.findByLoginName("plainmate").orElseThrow().getPasswordHash();

        bootstrapRunner.run(null);

        // 只补建 admin，既有管理员与普通用户原样不动
        AppUser admin = userRepo.findByLoginName("admin").orElseThrow();
        assertThat(admin.isPlatformAdmin()).isTrue();
        assertThat(encoder.matches(passwordFromFile(), admin.getPasswordHash())).isTrue();
        AppUser boss = userRepo.findByLoginName("firstboss").orElseThrow();
        assertThat(boss.isPlatformAdmin()).isTrue();
        assertThat(boss.getPasswordHash()).isEqualTo(bossHash);
        assertThat(userRepo.findByLoginName("plainmate").orElseThrow().getPasswordHash()).isEqualTo(mateHash);
    }

    // ---------- 改密删文件 ----------

    @Test
    void firstPasswordChange_deletesPasswordFile() throws Exception {
        bootstrapRunner.run(null);
        String initialPassword = passwordFromFile();
        assertThat(passwordFile).exists();

        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"admin\",\"password\":\"" + initialPassword + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/change-password").session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + initialPassword
                                + "\",\"newPassword\":\"Changed1234!\"}"))
                .andExpect(status().isNoContent());

        assertThat(passwordFile).doesNotExist();
        AppUser admin = userRepo.findByLoginName("admin").orElseThrow();
        assertThat(admin.isMustChangePassword()).isFalse();
        assertThat(encoder.matches("Changed1234!", admin.getPasswordHash())).isTrue();
    }

    // ---------- reset-admin 运维命令 ----------

    @Test
    void resetOps_existingAccount_rotatesPassword_promotes_audits() throws Exception {
        insertUser("opsuser", "OldPass1234", false, false);
        jdbc.update("update app_user set failed_attempts = 3 where login_name = 'opsuser'");
        int versionBefore = userRepo.findByLoginName("opsuser").orElseThrow().getPasswordVersion();

        int code = resetOpsRunner.runOps("opsuser");

        assertThat(code).isZero();
        AppUser after = userRepo.findByLoginName("opsuser").orElseThrow();
        assertThat(after.isPlatformAdmin()).isTrue();
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(after.getFailedAttempts()).isZero();
        assertThat(after.getLockedUntil()).isNull();
        assertThat(after.getPasswordVersion()).isEqualTo(versionBefore + 1);
        assertThat(encoder.matches("OldPass1234", after.getPasswordHash())).isFalse();
        assertThat(encoder.matches(passwordFromFile(), after.getPasswordHash())).isTrue();
        assertThat(auditCount("ADMIN_RESET_VIA_OPS")).isEqualTo(1);
    }

    @Test
    void resetOps_unknownLogin_returnsNonZero_createsNothing() {
        int code = resetOpsRunner.runOps("ghostuser");

        assertThat(code).isNotZero();
        assertThat(userRepo.findByLoginName("ghostuser")).isEmpty();
        assertThat(passwordFile).doesNotExist();
        assertThat(auditCount("ADMIN_RESET_VIA_OPS")).isZero();
    }

    @Test
    void resetRunner_withoutOpsArg_isNoOp() {
        // 正常服务启动路径：无 --ib.ops 参数时不动作、不退出进程
        resetOpsRunner.run(new DefaultApplicationArguments(new String[0]));
        assertThat(userRepo.findByLoginName("admin")).isEmpty();
        assertThat(passwordFile).doesNotExist();
    }
}
