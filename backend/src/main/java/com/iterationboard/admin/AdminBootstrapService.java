package com.iterationboard.admin;

import com.iterationboard.common.ApiException;
import com.iterationboard.common.AuditService;
import com.iterationboard.domain.AppUser;
import com.iterationboard.repo.UserRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P01 管理员初始化与应急重置（q3tbdxut，代初始化码方案）——供 install.bat / upgrade.bat / reset-admin.bat 调用：
 *
 *   POST /api/v1/admin/bootstrap        {loginName?, password}  仅当库中不存在该登录名时创建：
 *                                       显示姓名「平台管理员」、platformAdmin=true、mustChangePassword=true，
 *                                       密码只存 BCrypt 哈希。脚本负责生成随机密码、显示并写
 *                                       config\admin-initial-password.txt。
 *   GET  /api/v1/admin/bootstrap/status?loginName=admin         返回 {exists, mustChangePassword}：
 *                                       脚本轮询 mustChangePassword 变为 false 后删除密码文件
 *                                       （后端拦截层保证 admin 未改密前只能调改密接口，改密成功即清标记）。
 *   POST /api/v1/admin/reset-admin      {loginName, temporaryPassword}  把指定账号设为平台管理员 +
 *                                       临时密码 + mustChangePassword=true，并写审计日志（actor=目标账号本身）。
 *
 * 安全边界：三接口在 SecurityConfig 中 permitAll（安装/应急场景下尚无可用管理员会话），
 * 部署时应设置环境变量 APP_BOOTSTRAP_TOKEN，脚本以请求头 X-Bootstrap-Token 传入，服务强校验；
 * 未设置口令时 bootstrap 仍受「账号已存在即 409」的一次性约束，reset-admin 则退化为本机运维约定
 * （仅应在服务器电脑上由脚本调用，部署手册须提示设置口令）。
 */
@Service
public class AdminBootstrapService {
    public static final String DEFAULT_ADMIN_LOGIN = "admin";

    private final UserRepo userRepo;
    private final PasswordEncoder encoder;
    private final AuditService auditService;
    private final String bootstrapToken;

    public AdminBootstrapService(UserRepo userRepo, PasswordEncoder encoder, AuditService auditService,
                                 @Value("${app.bootstrap-token:}") String bootstrapToken) {
        this.userRepo = userRepo;
        this.encoder = encoder;
        this.auditService = auditService;
        this.bootstrapToken = bootstrapToken == null ? "" : bootstrapToken;
    }

    public void checkToken(HttpServletRequest request) {
        if (bootstrapToken.isBlank()) return;
        String provided = request.getHeader("X-Bootstrap-Token");
        if (!bootstrapToken.equals(provided)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "BOOTSTRAP_TOKEN_INVALID", "引导口令不正确");
        }
    }

    @Transactional
    public AppUser bootstrap(String loginName, String password) {
        String login = (loginName == null || loginName.isBlank()) ? DEFAULT_ADMIN_LOGIN : loginName.trim();
        if (userRepo.existsByLoginName(login)) {
            throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ALREADY_EXISTS",
                    "登录名 " + login + " 已存在，引导创建跳过（幂等保护）");
        }
        if (password == null || password.length() < 8) {
            throw ApiException.validation("初始密码至少 8 位（脚本应生成至少 16 位随机密码）");
        }
        AppUser u = new AppUser();
        u.setLoginName(login);
        u.setDisplayName("平台管理员");
        u.setPasswordHash(encoder.encode(password));
        u.setAvatarColor("indigo");
        u.setPlatformAdmin(true);
        u.setStatus("ACTIVE");
        u.setMustChangePassword(true);
        userRepo.save(u);
        auditService.log(null, u, "BOOTSTRAP_ADMIN", "USER", u.getId(),
                "安装/升级引导创建平台管理员账号 " + login);
        return u;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String loginName) {
        String login = (loginName == null || loginName.isBlank()) ? DEFAULT_ADMIN_LOGIN : loginName.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        var found = userRepo.findByLoginName(login);
        out.put("loginName", login);
        out.put("exists", found.isPresent());
        out.put("mustChangePassword", found.map(AppUser::isMustChangePassword).orElse(null));
        out.put("platformAdmin", found.map(AppUser::isPlatformAdmin).orElse(null));
        return out;
    }

    @Transactional
    public AppUser resetAdmin(String loginName, String temporaryPassword) {
        if (loginName == null || loginName.isBlank()) throw ApiException.validation("loginName 必填");
        if (temporaryPassword == null || temporaryPassword.length() < 8) {
            throw ApiException.validation("临时密码至少 8 位（脚本应生成至少 16 位随机密码）");
        }
        AppUser u = userRepo.findByLoginName(loginName.trim())
                .orElseThrow(() -> ApiException.notFound("账号不存在：" + loginName));
        u.setPlatformAdmin(true);
        u.setStatus("ACTIVE");
        u.setDeletedAt(null);
        u.setPasswordHash(encoder.encode(temporaryPassword));
        u.setMustChangePassword(true);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        u.setPasswordVersion(u.getPasswordVersion() + 1);
        userRepo.save(u);
        auditService.log(null, u, "RESET_ADMIN", "USER", u.getId(),
                "reset-admin 应急重置：设为平台管理员并生成临时密码（须首次登录改密）");
        return u;
    }
}
