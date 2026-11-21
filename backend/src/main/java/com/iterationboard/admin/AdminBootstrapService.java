package com.iterationboard.admin;

/**
 * 管理员初始化相关的共享常量。
 *
 * 历史（1.1.0-rc2 安全裁定 hsa43amn）：本类原先承载「脚本调 HTTP」方案的三个免登录接口
 * （POST /api/v1/admin/bootstrap、GET /api/v1/admin/bootstrap/status、POST /api/v1/admin/reset-admin，
 * 仅靠可选的 APP_BOOTSTRAP_TOKEN 防护、默认不校验），局域网内任何人可借此改密或自建管理员
 * 接管整个平台。rc2 已删除三接口、permitAll 配置与口令校验（checkToken）：
 * - 初始建号：应用内 {@link AdminBootstrapRunner} + {@link AdminBootstrapTxService}；
 * - 应急重置：`java -jar app.jar --ib.ops=reset-admin` 运维命令
 *   （{@link ResetAdminOpsRunner} + {@link ResetAdminOpsService}），只在本机进程内执行，不经 HTTP。
 * 本类现仅保留默认管理员登录名常量，供上述内部路径与 AdminOpsController 共用。
 */
public final class AdminBootstrapService {
    public static final String DEFAULT_ADMIN_LOGIN = "admin";

    private AdminBootstrapService() { }
}
