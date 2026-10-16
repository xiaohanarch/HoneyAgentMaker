package dev.eab.runtime;

import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.RunEvents.Suspended;
import dev.eab.tracing.SpanRecord.SpanKind;
import java.util.Map;

/**
 * 人工确认：第一次走到确认点时挂起运行，产出 confirmation_required 事件；用户决定后运行重放到这里，按决定继续。
 */
final class Confirmations {

    private Confirmations() {}

    /**
     * @return 用户是否同意
     * @throws Suspended 还没有决定，运行挂起
     */
    static boolean require(Exec exec, String segment, String title, Map<String, Object> preview) {
        String key = exec.key() + "/" + segment;
        var span = exec.span("人工确认", SpanKind.confirmation, segment);
        span.set("eab.confirmation.title", title);
        Boolean decision = exec.journal().decision(key);
        if (decision != null) {
            span.set("eab.confirmation.decision", decision ? "approved" : "rejected")
                    .end();
            return decision;
        }
        Journal.Pending pending = new Journal.Pending(key, "confirmation", title, preview);
        exec.journal().suspend(pending);
        exec.emit(RunEvent.of(
                "confirmation_required", exec.ctx().agent(), "key", key, "title", title, "preview", preview));
        // span 保持打开，挂起时以 suspended 落库，恢复后写入最终状态
        throw new Suspended(pending);
    }
}
