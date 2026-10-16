package dev.eab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentRunTest {

    @Test
    @DisplayName("参数解析：Agent、用户、话术、--yes")
    void parse() {
        var args = AgentRun.parse(new String[] {"vendor.pur/x", "--user", "zhangmin", "百润精密", "最近表现怎么样", "--yes"});
        assertThat(args).isEqualTo(new AgentRun.Args("vendor.pur/x", "zhangmin", "百润精密 最近表现怎么样", true, null));
        assertThat(AgentRun.parse(new String[] {"vendor.pur/x", "百润"})).isNull();
    }

    @Test
    @DisplayName("进程内跑通库存经营分析的确认流程：流式事件、确认、追踪树")
    void runsWithConfirmation() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int code = AgentRun.run(
                new AgentRun.Args("vendor.scm/inventory-risk-analyst", "sunkg", "为 M-0007 创建补货跟进任务", false, null),
                out,
                () -> true);
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertThat(code).as(text).isZero();
        assertThat(text).contains("需要确认", "已确认，继续执行", "已创建跟进任务 TASK-", "追踪", "confirmation", "approved");
    }

    @Test
    @DisplayName("没有的用户直接报错")
    void unknownUser() throws Exception {
        var bytes = new ByteArrayOutputStream();
        int code = AgentRun.run(
                new AgentRun.Args("vendor.pur/x", "nobody", "hi", true, null),
                new PrintStream(bytes, true, StandardCharsets.UTF_8),
                () -> true);
        assertThat(code).isEqualTo(2);
    }
}
