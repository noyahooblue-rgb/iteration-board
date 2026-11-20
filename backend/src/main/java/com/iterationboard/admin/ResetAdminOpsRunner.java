package com.iterationboard.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 运维命令模式 reset-admin（1.1.0 裁定第一步）：
 *   java -jar app.jar --ib.ops=reset-admin [--login=&lt;登录名&gt;]（默认 admin）
 * 主类检测到 --ib.ops 后以 WebApplicationType.NONE + ops profile 启动（不占端口、不跑定时任务），
 * 本 runner 在 {@link AdminBootstrapRunner}（@Order 100）之后执行（@Order 200）：
 * 故 login=admin 且不存在时 bootstrap 已先建好，这里只管重置。
 * 完成后以退出码 0/非 0 结束进程；控制台只打密码文件路径，绝不打印密码本身（bat 负责 type 文件）。
 * 无 --ib.ops 参数时（正常服务启动）本 runner 什么都不做。
 */
@Component
@Order(200)
public class ResetAdminOpsRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ResetAdminOpsRunner.class);
    static final String OPS_OPTION = "ib.ops";
    static final String OPS_RESET_ADMIN = "reset-admin";

    private final ResetAdminOpsService opsService;
    private final ApplicationContext context;
    private final Path passwordFile;

    public ResetAdminOpsRunner(ResetAdminOpsService opsService, ApplicationContext context,
                               @Value("${app.bootstrap-password-file:config/admin-initial-password.txt}")
                               String passwordFile) {
        this.opsService = opsService;
        this.context = context;
        this.passwordFile = Path.of(passwordFile);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args == null || !args.containsOption(OPS_OPTION)) {
            return; // 正常服务模式，不动作、不退出
        }
        List<String> opsValues = args.getOptionValues(OPS_OPTION);
        String ops = (opsValues == null || opsValues.isEmpty()) ? "" : opsValues.get(0);
        int code;
        if (OPS_RESET_ADMIN.equals(ops)) {
            List<String> logins = args.getOptionValues("login");
            String login = (logins == null || logins.isEmpty() || logins.get(0).isBlank())
                    ? AdminBootstrapService.DEFAULT_ADMIN_LOGIN : logins.get(0).trim();
            code = runOps(login);
        } else {
            System.err.println("unknown ops command: " + ops);
            log.error("未知运维命令 --ib.ops={}", ops);
            code = 2;
        }
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /**
     * 执行一次 reset-admin：重置账号 → 写密码文件 → 控制台打印路径与完成行。
     * 返回 0 成功；账号不存在或任何异常返回 1 且不创建账号。抽出独立方法供测试直接调用（不触发进程退出）。
     */
    public int runOps(String login) {
        try {
            String password = opsService.resetAdmin(login);
            AdminPasswordSupport.writePasswordFile(passwordFile, password);
            System.out.println("reset-admin: new initial password written to " + passwordFile);
            System.out.println("reset-admin done for login=" + login);
            return 0;
        } catch (Exception e) {
            System.err.println("reset-admin failed for login=" + login + ": " + e.getMessage());
            log.error("reset-admin 运维命令失败（login={}）", login, e);
            return 1;
        }
    }
}
