package dev.eab.platform.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.platform.http.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 推送回调内容的解析：三种 StreamResponse 形态和直接的 Task，状态名归一 */
class A2aCallbackParseTest {

    @Test
    @DisplayName("statusUpdate 取状态和状态消息里的文字")
    void statusUpdate() {
        var update = A2aCallbackController.parse(
                "{\"statusUpdate\":{\"taskId\":\"t\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\","
                        + "\"message\":{\"parts\":[{\"text\":\"完成\"}]}}}}");
        assertThat(update.state()).isEqualTo("completed");
        assertThat(update.text()).isEqualTo("完成");
    }

    @Test
    @DisplayName("artifactUpdate 只带产出物文字，不改变状态；append 标记保留")
    void artifactUpdate() {
        var update = A2aCallbackController.parse(
                "{\"artifactUpdate\":{\"append\":true,\"artifact\":{\"parts\":[{\"text\":\"风险等级 高\"}]}}}");
        assertThat(update.state()).isNull();
        assertThat(update.text()).isEqualTo("风险等级 高");
        assertThat(update.append()).isTrue();
    }

    @Test
    @DisplayName("task 和直接的 Task：产出物优先于状态消息")
    void task() {
        var update = A2aCallbackController.parse("{\"task\":{\"status\":{\"state\":\"input-required\","
                + "\"message\":{\"parts\":[{\"text\":\"哪家？\"}]}},\"artifacts\":[]}}");
        assertThat(update.state()).isEqualTo("input-required");
        assertThat(update.text()).isEqualTo("哪家？");
        var direct = A2aCallbackController.parse("{\"status\":{\"state\":\"TASK_STATE_CANCELLED\"},"
                + "\"artifacts\":[{\"parts\":[{\"text\":\"A\"}]},{\"parts\":[{\"text\":\"B\"}]}]}");
        assertThat(direct.state()).isEqualTo("canceled");
        assertThat(direct.text()).isEqualTo("A\nB");
    }

    @Test
    @DisplayName("不是 JSON 时返回 400")
    void invalid() {
        assertThatThrownBy(() -> A2aCallbackController.parse("not json"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status()).isEqualTo(400));
    }
}
