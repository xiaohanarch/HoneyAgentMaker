package dev.eab.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** EAB_DEMO_PASSCODES 的解析与查找 */
class DemoPasscodesTest {

    @Test
    @DisplayName("未配置或全空白时不启用门禁")
    void empty() {
        assertThat(DemoPasscodes.parse(null).required()).isFalse();
        assertThat(DemoPasscodes.parse("").required()).isFalse();
        assertThat(DemoPasscodes.parse("  ").required()).isFalse();
    }

    @Test
    @DisplayName("解析 user:pass 列表：条目前后空白与空段被忽略，口令里可以有冒号")
    void parse() {
        var passcodes = DemoPasscodes.parse(" zhangmin:s3cret , *:shared ,, liqiang:p@ss:word ");
        assertThat(passcodes.required()).isTrue();
        assertThat(passcodes.lookup("zhangmin")).isEqualTo("s3cret");
        assertThat(passcodes.lookup("liqiang")).isEqualTo("p@ss:word");
        // 没有单列条目的身份回退到 * 缺省
        assertThat(passcodes.lookup("wangfang")).isEqualTo("shared");
    }

    @Test
    @DisplayName("口令表没有缺省条目时，未配置的身份查不到口令")
    void lookupWithoutWildcard() {
        var passcodes = DemoPasscodes.parse("zhangmin:only-me");
        assertThat(passcodes.lookup("zhangmin")).isEqualTo("only-me");
        assertThat(passcodes.lookup("wangfang")).isNull();
    }

    @Test
    @DisplayName("格式不对（缺冒号、空用户名、空口令）时解析抛错，让配置错误在启动时暴露")
    void malformed() {
        assertThatThrownBy(() -> DemoPasscodes.parse("zhangmin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user:pass");
        assertThatThrownBy(() -> DemoPasscodes.parse("zhangmin:")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DemoPasscodes.parse(":pass")).isInstanceOf(IllegalArgumentException.class);
    }
}
