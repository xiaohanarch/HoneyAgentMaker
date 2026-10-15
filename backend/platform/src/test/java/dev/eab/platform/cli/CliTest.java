package dev.eab.platform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.testing.Fixtures;
import dev.eab.spec.testing.RepoPaths;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CliTest {

    static final class Capture {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final PrintStream stream = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("assets:validate：仓库资产校验通过，退出码 0")
    void validate() {
        Capture out = new Capture();
        assertThat(ValidateAssets.run(RepoPaths.assets(), out.stream)).isZero();
        assertThat(out.text()).contains("0 个错误").contains("校验通过");
    }

    @Test
    @DisplayName("assets:assemble：租户视角下标品指令显示为占位块，租户追加内容可见")
    void assembleAsTenant() {
        Capture out = new Capture();
        Capture err = new Capture();
        int code = AssembleAgent.run(
                List.of("vendor.fin/transaction-anomaly-analyst", "--tenant", "t001"),
                RepoPaths.assets(),
                out.stream,
                err.stream);
        assertThat(code).isZero();
        assertThat(out.text())
                .contains("🔒 标品指令（不可见，运行时注入）")
                .contains("单笔金额超过 50 万元的异常")
                .doesNotContain("你是交易异常解析专家");
    }

    @Test
    @DisplayName("assets:assemble：缺少参数时提示用法，Agent 不存在时退出码 1")
    void assembleErrors() {
        Capture out = new Capture();
        Capture err = new Capture();
        assertThat(AssembleAgent.run(List.of(), RepoPaths.assets(), out.stream, err.stream))
                .isEqualTo(2);
        assertThat(AssembleAgent.run(
                        List.of("vendor.fin/missing", "--as", "vendor"), RepoPaths.assets(), out.stream, err.stream))
                .isEqualTo(1);
        assertThat(err.text()).contains("AGENT_NOT_FOUND");
        assertThat(Fixtures.VENDOR_ANOMALY_INSTRUCTIONS).isNotEmpty();
    }
}
