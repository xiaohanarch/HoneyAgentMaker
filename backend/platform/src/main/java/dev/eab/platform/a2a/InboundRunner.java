package dev.eab.platform.a2a;

import dev.eab.a2a.InboundExecutor;
import dev.eab.platform.assist.AssistService;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.runtime.RunEvents;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 入站 A2A 请求在平台一侧的运行：岗位助手走统一入口的第二级路由（第一级固定为这个助手），Agent 直接运行。
 * 两条路径都经过四处鉴权：入口（岗位助手的角色）、Agent 作用域、工具网关、BO API；身份就是调用方带来的用户令牌。
 */
final class InboundRunner implements InboundExecutor.Runner {

    /** 调用上下文里的调用方：用户身份，加上调用方自报的平台名（请求头 X-A2A-Caller，只用于审计） */
    record Caller(Principal principal, String caller) {}

    static final Pattern APPROVE =
            Pattern.compile("^\\s*(确认|同意|批准|是|好的?|可以|yes|y|approve)\\s*[。.!！]?\\s*$", Pattern.CASE_INSENSITIVE);

    private final InboundAgents.Exposed target;
    private final AssistService assist;
    private final RuntimeService runtime;

    InboundRunner(InboundAgents.Exposed target, AssistService assist, RuntimeService runtime) {
        this.target = target;
        this.assist = assist;
        this.runtime = runtime;
    }

    /** 从运行事件里收集结果：最终回复、等待确认的事项、错误 */
    static final class Collector implements RunEvents.Sink {
        final Map<String, Object> seen = new LinkedHashMap<>();
        private final Consumer<String> progress;

        Collector(Consumer<String> progress) {
            this.progress = progress;
        }

        @Override
        public void accept(RunEvents.RunEvent event) {
            switch (event.type()) {
                case "run_started" -> {
                    seen.put("runId", event.data().get("runId"));
                    seen.put("traceId", event.data().get("traceId"));
                }
                case "tool_call" -> progress.accept("正在调用 " + event.data().get("tool"));
                case "final" -> seen.put("text", event.data().get("text"));
                case "suspended" -> seen.put("suspended", event.data().get("title"));
                case "error" -> seen.put("error", event.data().get("message"));
                default -> {}
            }
        }

        String get(String key) {
            Object value = seen.get(key);
            return value == null ? null : String.valueOf(value);
        }
    }

    @Override
    public InboundExecutor.Reply run(
            Object principal, String text, String sessionId, String pendingRunId, Consumer<String> progress) {
        if (!(principal instanceof Caller caller)) {
            // 控制器已经拦截了没有身份的请求；这里再兜一次，任何情况下都不以技术账号运行
            return new InboundExecutor.Reply("failed", "身份透传失败：入站请求没有用户身份，拒绝执行", null, null);
        }
        Principal p = caller.principal();
        Collector events = new Collector(progress);
        if (pendingRunId != null) {
            boolean approved = APPROVE.matcher(text).matches();
            var outcome = runtime.confirm(pendingRunId, p.user(), p.token(), approved, events);
            return reply(events, outcome.runId(), outcome.traceId());
        }
        if (target.kind().equals("assistant")) {
            var outcome = assist.assist(
                    new AssistService.AssistRequest(p.user(), p.token(), text, sessionId, target.ref()), events);
            if (!outcome.kind().equals("run")) {
                Object message = outcome.payload().get("message");
                return new InboundExecutor.Reply(
                        outcome.kind().equals("clarify") ? "input-required" : "completed",
                        clarify(message, outcome.payload()),
                        null,
                        outcome.traceId());
            }
            return reply(events, outcome.runId(), outcome.traceId());
        }
        var outcome = runtime.start(
                new RuntimeService.StartRequest(p.user(), p.token(), target.ref(), text, sessionId, null), events);
        return reply(events, outcome.runId(), outcome.traceId());
    }

    /** 澄清时把选项一并列出，调用方下一轮用更明确的话术重问 */
    static String clarify(Object message, Map<String, Object> payload) {
        StringBuilder text = new StringBuilder(message == null ? "" : String.valueOf(message));
        if (payload.get("options") instanceof java.util.List<?> options) {
            for (Object option : options) {
                if (option instanceof Map<?, ?> o)
                    text.append("\n- ").append(o.get("name")).append("：").append(o.get("description"));
            }
        }
        return text.toString();
    }

    static InboundExecutor.Reply reply(Collector events, String runId, String traceId) {
        if (events.get("suspended") != null) {
            return new InboundExecutor.Reply(
                    "input-required", "这一步需要确认：" + events.get("suspended") + "。回复“确认”继续，回复其他内容取消。", runId, traceId);
        }
        if (events.get("error") != null && events.get("text") == null) {
            return new InboundExecutor.Reply("failed", events.get("error"), runId, traceId);
        }
        return new InboundExecutor.Reply(
                "completed", events.get("text") == null ? "" : events.get("text"), runId, traceId);
    }
}
