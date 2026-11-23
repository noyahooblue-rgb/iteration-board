package com.iterationboard.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 初始/重置密码的生成与密码文件读写（1.1.0 裁定第一步：业务初始化收进应用）。
 *
 * 密码生成自 rc3（修订六契约 1）起委托 {@link com.iterationboard.auth.PasswordPolicy}：
 * 默认 20 位，含大写/小写/数字/特殊各至少一位，特殊只从 {@code @#*-_=+.?} 选，
 * 字母/数字去掉易混字符（0/O/1/l/I）。特殊字符集中不含空格，密码文件仍可被
 * 运行器 ps1 用正则 'admin initial password:\s*(\S+)' 解析。
 *
 * 密码文件格式与现 bat 版逐字一致：
 *   第一行 admin initial password: &lt;密码&gt;
 *   第二行 Created by the application. This file is deleted automatically after the admin password is changed.
 * 密码只进文件，绝不进日志。
 */
final class AdminPasswordSupport {

    static final int PASSWORD_LENGTH = 20;

    static final String FILE_SECOND_LINE =
            "Created by the application. This file is deleted automatically after the admin password is changed.";

    private AdminPasswordSupport() {}

    static String generatePassword() {
        return com.iterationboard.auth.PasswordPolicy.generate(PASSWORD_LENGTH);
    }

    /** 指定长度版（管理员重置他人密码用 16 位，契约 2）。 */
    static String generatePassword(int length) {
        return com.iterationboard.auth.PasswordPolicy.generate(length);
    }

    /** 写密码文件：父目录不存在则创建；写完尽力把权限收紧为仅所有者读写（非 POSIX 文件系统忽略）。 */
    static void writePasswordFile(Path file, String password) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String content = "admin initial password: " + password + "\n" + FILE_SECOND_LINE + "\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, perms);
        } catch (Exception ignored) {
            // 非 POSIX 文件系统（如 Windows NTFS）不支持该调用，权限由安装脚本的 icacls 负责，忽略
        }
    }
}
