package com.iterationboard.auth;

import com.iterationboard.common.ApiException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 密码规则（修订六 §4，rc3 契约 1）：全平台唯一口径，注册 / 改密 / 强制改密 / 管理员重置共用。
 * 规则：8–64 位；同时含字母、数字、特殊字符（非字母数字且非空白的可见字符）；
 * 不能与登录名相同或包含登录名（大小写不敏感，loginName 非空时）。
 * 系统生成密码：含大写、小写、数字、特殊各至少一位；特殊只从 {@code @#*-_=+.?} 选
 * （避开 " ' ^ &amp; | &lt; &gt; ! % 等在 cmd/SQL/URL 中有特殊含义的字符）；
 * 字母/数字沿用去混淆字母表（大写去 I/O、小写去 l、数字去 0/1）。
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 64;

    // 大写去掉 I、O；小写去掉 l；数字去掉 0、1（易混字符，与 AdminPasswordSupport 旧口径一致）
    static final String UPPERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String LOWERS = "abcdefghijkmnopqrstuvwxyz";
    static final String DIGITS = "23456789";
    static final String SPECIALS = "@#*-_=+.?";
    private static final String ALL = UPPERS + LOWERS + DIGITS + SPECIALS;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordPolicy() {}

    /** 特殊字符 = 非字母、非数字、非空白的字符（如键盘可见符号）。 */
    static boolean isSpecial(char c) {
        return !Character.isLetter(c) && !Character.isDigit(c) && !Character.isWhitespace(c);
    }

    private static boolean hasLetter(String s) { return s.chars().anyMatch(Character::isLetter); }
    private static boolean hasDigit(String s) { return s.chars().anyMatch(Character::isDigit); }
    private static boolean hasSpecial(String s) { return s.chars().anyMatch(c -> isSpecial((char) c)); }

    private static boolean containsLoginName(String password, String loginName) {
        if (loginName == null || loginName.isBlank()) return false;
        return password.toLowerCase(Locale.ROOT).contains(loginName.toLowerCase(Locale.ROOT));
    }

    /** 同规则布尔版（不抛异常）：登录时判定老密码是否合规用。 */
    public static boolean complies(String password, String loginName) {
        if (password == null) return false;
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) return false;
        if (!hasLetter(password) || !hasDigit(password) || !hasSpecial(password)) return false;
        return !containsLoginName(password, loginName);
    }

    /** 校验并在不合规时抛 validation，文案逐条具体。loginName 可为 null（只校验长度/三类）。 */
    public static void validate(String password, String loginName) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw ApiException.validation("密码须为 8–64 位");
        }
        if (!hasLetter(password) || !hasDigit(password) || !hasSpecial(password)) {
            throw ApiException.validation("密码须同时包含字母、数字和特殊字符");
        }
        if (containsLoginName(password, loginName)) {
            throw ApiException.validation("密码不能包含登录名");
        }
    }

    /** 无 loginName 的校验（长度 + 三类）。 */
    public static void validate(String password) {
        validate(password, null);
    }

    /**
     * 系统生成密码：指定长度（调用方按场景传 16 或 20），大写/小写/数字/特殊各至少一位，
     * 特殊只出自 {@link #SPECIALS}，其余字符为去混淆字母表，Fisher-Yates 洗牌。
     */
    public static String generate(int length) {
        if (length < 4) throw new IllegalArgumentException("生成密码长度至少 4 位");
        List<Character> chars = new ArrayList<>(length);
        chars.add(pick(UPPERS));
        chars.add(pick(LOWERS));
        chars.add(pick(DIGITS));
        chars.add(pick(SPECIALS));
        for (int i = chars.size(); i < length; i++) {
            chars.add(pick(ALL));
        }
        for (int i = chars.size() - 1; i > 0; i--) {
            Collections.swap(chars, i, RANDOM.nextInt(i + 1));
        }
        StringBuilder sb = new StringBuilder(length);
        for (char c : chars) sb.append(c);
        return sb.toString();
    }

    private static char pick(String alphabet) {
        return alphabet.charAt(RANDOM.nextInt(alphabet.length()));
    }
}
