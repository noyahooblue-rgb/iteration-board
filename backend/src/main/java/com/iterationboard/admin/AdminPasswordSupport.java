package com.iterationboard.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 初始/重置密码的生成与密码文件读写（1.1.0 裁定第一步：业务初始化收进应用）。
 *
 * 密码规格：20 位，大写+小写+数字三类各至少一位，字符集去掉易混字符（0/O/1/l/I），
 * 因此也不含任何对 cmd/SQL/URL 有特殊含义的字符，可安全地被脚本 type/正则解析。
 *
 * 密码文件格式与现 bat 版逐字一致（运行器 ps1 用正则 'admin initial password:\s*(\S+)' 解析）：
 *   第一行 admin initial password: &lt;密码&gt;
 *   第二行 Created by the application. This file is deleted automatically after the admin password is changed.
 * 密码只进文件，绝不进日志。
 */
final class AdminPasswordSupport {

    // 大写去掉 I、O；小写去掉 l；数字去掉 0、1（易混字符）
    private static final String UPPERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWERS = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = UPPERS + LOWERS + DIGITS;
    static final int PASSWORD_LENGTH = 20;

    static final String FILE_SECOND_LINE =
            "Created by the application. This file is deleted automatically after the admin password is changed.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AdminPasswordSupport() {}

    static String generatePassword() {
        List<Character> chars = new ArrayList<>(PASSWORD_LENGTH);
        chars.add(pick(UPPERS));
        chars.add(pick(LOWERS));
        chars.add(pick(DIGITS));
        for (int i = chars.size(); i < PASSWORD_LENGTH; i++) {
            chars.add(pick(ALL));
        }
        // Fisher-Yates 洗牌，避免三类保底字符固定落在前三位
        for (int i = chars.size() - 1; i > 0; i--) {
            Collections.swap(chars, i, RANDOM.nextInt(i + 1));
        }
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (char c : chars) sb.append(c);
        return sb.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
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
