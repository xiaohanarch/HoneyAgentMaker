package dev.eab.cli;

import dev.eab.directory.DemoDirectory;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.spec.Json;
import dev.eab.tracing.SpanRecord;
import dev.eab.tracing.SpanRecord.SpanStatus;
import dev.eab.tracing.TraceView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * pnpm agent:run vendor.pur/supplier-performance-analyst --user zhangmin "百润精密最近表现怎么样" [--yes]
 *
 * <p>在进程内启动一个平台（加载仓库资产，签名入库）和一个 DemoERP（内存库，灌入种子数据），两者监听本机的随机端口，
 * 链路与真实部署相同，不依赖 pnpm demo，断网也能跑。终端里流式打印运行事件，结束后打印追踪树；遇到确认时提示 y/n，
 * 加 --yes 时自动确认。模型提供方由环境变量决定，默认脚本化模型。
 */
public final class AgentRun {

    private AgentRun() {}

    record Args(String agent, String user, String message, boolean yes, String delegationId) {}

    static Args parse(String[] argv) {
        String agent = null;
        String user = null;
        String delegation = null;
        boolean yes = false;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < argv.length; i++) {
            switch (argv[i]) {
                case "--user" -> user = i + 1 < argv.length ? argv[++i] : null;
                case "--delegation" -> delegation = i + 1 < argv.length ? argv[++i] : null;
                case "--yes", "-y" -> yes = true;
                default -> {
                    if (agent == null && argv[i].contains("/")) agent = argv[i];
                    else rest.add(argv[i]);
                }
            }
        }
        if (agent == null || user == null || rest.isEmpty()) return null;
        return new Args(agent, user, String.join(" ", rest), yes, delegation);
    }

    static final String USAGE =
            "用法：pnpm agent:run <namespace/name> --user <用户> \"<话术>\" [--yes] [--delegation <委托编号>]\n"
                    + "例如：pnpm agent:run vendor.pur/supplier-performance-analyst --user zhangmin \"百润精密最近表现怎么样\"";

    /** 运行事件的终端输出 */
    static void print(PrintStream out, RunEvent event) {
        Map<String, Object> d = event.data();
        String agent = event.agent() == null ? "" : "[" + event.agent() + "] ";
        switch (event.type()) {
            case "run_started" -> out.println(Ansi.dim("运行 " + d.get("runId") + "，追踪 " + d.get("traceId")));
            case "step" -> out.println(Ansi.cyan("· " + agent + d.get("text")));
            case "tool_call" ->
                out.println(Ansi.yellow(
                        "→ " + agent + d.get("tool") + " " + Json.MAPPER.writeValueAsString(d.get("arguments"))));
            case "tool_result" ->
                out.println((Boolean.TRUE.equals(d.get("ok")) ? Ansi.green("← ") : Ansi.red("✗ ")) + d.get("tool") + "："
                        + d.get("summary"));
            case "confirmation_required" ->
                out.println(Ansi.magenta("? 需要确认：" + d.get("title")) + "\n  "
                        + Json.MAPPER.writeValueAsString(d.get("preview")));
            case "text_delta" -> {
                out.print(d.get("delta"));
                out.flush();
            }
            case "final" -> out.println();
            case "error" -> out.println(Ansi.red("运行失败：" + d.get("message")));
            default -> {}
        }
    }

    /** 追踪树：种类、名称、耗时、状态和关键属性 */
    static void printTree(PrintStream out, List<SpanRecord> spans) {
        out.println(Ansi.bold("追踪"));
        for (TraceView.Node root : TraceView.tree(spans)) printNode(out, root, "", true);
    }

    private static void printNode(PrintStream out, TraceView.Node node, String prefix, boolean last) {
        SpanRecord span = node.span();
        long millis = Duration.between(Instant.parse(span.startTime()), Instant.parse(span.endTime()))
                .toMillis();
        String status = span.status() == SpanStatus.ok
                ? ""
                : " " + Ansi.red(span.status().name());
        String detail = detail(span);
        out.println(prefix + (last ? "└─ " : "├─ ") + Ansi.dim(span.kind().name()) + " " + span.name()
                + Ansi.dim(" " + millis + "ms") + status + (detail.isEmpty() ? "" : Ansi.dim("  " + detail)));
        List<TraceView.Node> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            printNode(out, children.get(i), prefix + (last ? "   " : "│  "), i == children.size() - 1);
        }
    }

    private static String detail(SpanRecord span) {
        Map<String, Object> a = span.attributes();
        return switch (span.kind()) {
            case policy ->
                a.containsKey("eab.policy.rule")
                        ? a.get("eab.policy.rule") + " " + a.getOrDefault("eab.policy.reason", "")
                        : "";
            case model ->
                a.getOrDefault("gen_ai.request.model", "") + " 输入 " + a.getOrDefault("gen_ai.usage.input_tokens", 0)
                        + " 输出 " + a.getOrDefault("gen_ai.usage.output_tokens", 0) + " token";
            case guard -> String.valueOf(a.getOrDefault("eab.guard.result", ""));
            case confirmation -> String.valueOf(a.getOrDefault("eab.confirmation.decision", ""));
            default -> "";
        };
    }

    static int run(Args args, PrintStream out, BooleanSupplier confirm) throws Exception {
        var user = DemoDirectory.findUser(args.user()).orElse(null);
        if (user == null) {
            out.println(Ansi.red("没有用户 " + args.user()));
            return 2;
        }
        out.println(Ansi.dim("正在启动进程内的平台与 DemoERP…"));
        try (DemoEnvironment env = new DemoEnvironment()) {
            String token = env.services().identity().login(user.id()).token();
            RuntimeService runtime = env.runtime();
            var outcome = runtime.start(
                    new RuntimeService.StartRequest(
                            user,
                            args.delegationId() == null ? token : null,
                            args.agent(),
                            args.message(),
                            null,
                            args.delegationId()),
                    e -> print(out, e));
            while (outcome.result().status().equals("suspended")) {
                boolean approved = args.yes() || confirm.getAsBoolean();
                out.println(Ansi.dim(approved ? "已确认，继续执行" : "已拒绝"));
                outcome = runtime.confirm(outcome.runId(), user, token, approved, e -> print(out, e));
            }
            out.println();
            printTree(out, runtime.traces().list(outcome.traceId()));
            var result = outcome.result();
            if (result.unverified() != null && !result.unverified().isEmpty()) {
                out.println(Ansi.yellow("未经核实的引用：" + String.join("、", result.unverified())));
            }
            if (result.output() != null)
                out.println(Ansi.dim("结构化结果：" + Json.MAPPER.writeValueAsString(result.output())));
            return result.status().equals("completed") ? 0 : 1;
        }
    }

    public static void main(String[] argv) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        // scripts/java-cli.mjs 经环境变量传一份参数：Windows 上命令行参数里的中文会被 ANSI 代码页弄乱
        String fromEnv = System.getenv("EAB_CLI_ARGS");
        if (fromEnv != null && !fromEnv.isBlank()) argv = Json.MAPPER.readValue(fromEnv, String[].class);
        Args args = parse(argv);
        if (args == null) {
            out.println(USAGE);
            System.exit(2);
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        int code = run(args, out, () -> {
            out.print("确认执行？[y/N] ");
            out.flush();
            try {
                String line = in.readLine();
                return line != null && line.strip().equalsIgnoreCase("y");
            } catch (java.io.IOException e) {
                return false;
            }
        });
        System.exit(code);
    }

    /** 终端颜色；NO_COLOR 或输出不是终端时不加颜色 */
    static final class Ansi {
        static final boolean ENABLED = System.getenv("NO_COLOR") == null && System.console() != null;

        static String color(String code, String text) {
            return ENABLED ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
        }

        static String red(String t) {
            return color("31", t);
        }

        static String green(String t) {
            return color("32", t);
        }

        static String yellow(String t) {
            return color("33", t);
        }

        static String magenta(String t) {
            return color("35", t);
        }

        static String cyan(String t) {
            return color("36", t);
        }

        static String bold(String t) {
            return color("1", t);
        }

        static String dim(String t) {
            return color("2", t);
        }
    }
}
