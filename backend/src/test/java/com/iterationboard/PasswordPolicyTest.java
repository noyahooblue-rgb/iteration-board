package com.iterationboard;

import com.iterationboard.auth.PasswordPolicy;
import com.iterationboard.common.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码规则单元测试（修订六契约 1）：不合规组合矩阵 + 系统生成密码 1000 次全合规。
 * 纯虚构示例数据。
 */
class PasswordPolicyTest {

    private static final String SPECIALS_ALLOWED = "@#*-_=+.?";
    private static final String FORBIDDEN = "\"'^&|<>!%";

    @Test
    void validate_rejectsNonCompliantMatrix() {
        // 缺特殊字符
        assertThatThrownBy(() -> PasswordPolicy.validate("Abcdef12", "alice"))
                .isInstanceOf(ApiException.class).hasMessageContaining("字母、数字和特殊字符");
        // 缺字母
        assertThatThrownBy(() -> PasswordPolicy.validate("1234567!", "alice"))
                .isInstanceOf(ApiException.class).hasMessageContaining("字母、数字和特殊字符");
        // 缺数字
        assertThatThrownBy(() -> PasswordPolicy.validate("Abcdefg!", "alice"))
                .isInstanceOf(ApiException.class).hasMessageContaining("字母、数字和特殊字符");
        // 7 位
        assertThatThrownBy(() -> PasswordPolicy.validate("Ab1!xyz", "alice"))
                .isInstanceOf(ApiException.class).hasMessageContaining("8–64 位");
        // 65 位
        assertThatThrownBy(() -> PasswordPolicy.validate("Aa1!" + "a".repeat(61), "alice"))
                .isInstanceOf(ApiException.class).hasMessageContaining("8–64 位");
        // 包含登录名（大小写不敏感）
        assertThatThrownBy(() -> PasswordPolicy.validate("xxALICE#99", "alice"))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能包含登录名");
        // 与登录名相同
        assertThatThrownBy(() -> PasswordPolicy.validate("Alice#123", "Alice#123"))
                .isInstanceOf(ApiException.class).hasMessageContaining("不能包含登录名");
        // null
        assertThatThrownBy(() -> PasswordPolicy.validate(null, "alice"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void complies_matchesValidateMatrix() {
        assertThat(PasswordPolicy.complies("Abcdef12", "alice")).isFalse();
        assertThat(PasswordPolicy.complies("1234567!", "alice")).isFalse();
        assertThat(PasswordPolicy.complies("Abcdefg!", "alice")).isFalse();
        assertThat(PasswordPolicy.complies("Ab1!xyz", "alice")).isFalse();
        assertThat(PasswordPolicy.complies("Aa1!" + "a".repeat(61), "alice")).isFalse();
        assertThat(PasswordPolicy.complies("xxALICE#99", "alice")).isFalse();
        assertThat(PasswordPolicy.complies("Alice#123", "Alice#123")).isFalse();
        assertThat(PasswordPolicy.complies(null, "alice")).isFalse();
        assertThat(PasswordPolicy.complies("Passw0rd!1", "alice")).isTrue();
        assertThat(PasswordPolicy.complies("Passw0rd!1", null)).isTrue();
    }

    @Test
    void validate_acceptsCompliant() {
        PasswordPolicy.validate("Passw0rd!1", "alice");
        PasswordPolicy.validate("aB3#xxxx", "bob");
        PasswordPolicy.validate("Aa1!" + "a".repeat(60), "carol"); // 恰好 64 位
    }

    @Test
    void generate_16_alwaysCompliant_1000() {
        for (int i = 0; i < 1000; i++) {
            String pw = PasswordPolicy.generate(16);
            assertThat(pw).hasSize(16);
            assertGeneratedShape(pw);
        }
    }

    @Test
    void generate_20_alwaysCompliant_1000() {
        for (int i = 0; i < 1000; i++) {
            String pw = PasswordPolicy.generate(20);
            assertThat(pw).hasSize(20);
            assertGeneratedShape(pw);
        }
    }

    private void assertGeneratedShape(String pw) {
        assertThat(PasswordPolicy.complies(pw, null)).as("生成密码须合规: %s", pw).isTrue();
        assertThat(pw.chars().anyMatch(Character::isUpperCase)).isTrue();
        assertThat(pw.chars().anyMatch(Character::isLowerCase)).isTrue();
        assertThat(pw.chars().anyMatch(Character::isDigit)).isTrue();
        // 特殊字符只出自允许集
        pw.chars().filter(c -> !Character.isLetterOrDigit(c))
                .forEach(c -> assertThat(SPECIALS_ALLOWED).contains(String.valueOf((char) c)));
        // 不含禁用字符
        for (char f : FORBIDDEN.toCharArray()) {
            assertThat(pw).doesNotContain(String.valueOf(f));
        }
    }
}
