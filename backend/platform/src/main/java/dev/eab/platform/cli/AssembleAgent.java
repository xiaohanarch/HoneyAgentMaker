package dev.eab.platform.cli;

import dev.eab.platform.PlatformProperties;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * pnpm assets:assemble &lt;namespace/name&gt; [--tenant t001] [--as tenant|vendor|platform] [--json]
 * 打印有效定义，用于自测组装结果（主提示词 5.4 节）。租户视角下标品指令和平台规则原文显示为占位块。
 */
public final class AssembleAgent {

    private AssembleAgent() {}

    private static final Map<String, String> LAYER_LABEL = Map.of("platform", "平台", "vendor", "标品", "tenant", "租户");

    private static final Map<String, String> SEGMENT_TITLE = Map.of(
            "platform-rule", "平台安全规则",
            "vendor-instructions", "标品指令",
            "vendor-context", "标品语气与风格",
            "vendor-constraint", "标品约束",
            "semantic-instruction", "语义包指令",
            "tenant-instructions", "租户追加指令",
            "tenant-constraint", "租户追加约束");

    static String tag(String layer) {
        String label = "[" + LAYER_LABEL.get(layer) + "]";
        return switch (layer) {
            case "vendor" -> Ansi.magenta(label);
            case "tenant" -> Ansi.cyan(label);
            default -> Ansi.gray(label);
        };
    }

    static String heading(String text) {
        return Ansi.bold("\n── " + text + " " + "─".repeat(Math.max(0, 40 - text.length() * 2)));
    }

    static String indent(String text) {
        return String.join("\n", text.strip().lines().map(l -> "    " + l).toList());
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    static String renderSegment(JsonNode s) {
        String label = text(s, "label");
        String title = SEGMENT_TITLE.get(text(s, "kind")) + (label == null ? "" : " " + label);
        String meta = Ansi.dim(text(s, "source") + "  " + text(s, "field"));
        JsonNode body = s.get("text");
        String rendered = Visibility.isRedacted(body)
                ? Ansi.dim("    🔒 " + body.get("label").asString() + "  #"
                        + body.get("digest").asString())
                : "tenant".equals(text(s, "layer")) ? Ansi.cyan(indent(body.asString())) : indent(body.asString());
        return tag(text(s, "layer")) + " " + title + "  " + meta + "\n" + rendered;
    }

    static String render(JsonNode d, Viewer viewer) {
        JsonNode p = d.get("provenance");
        List<String> lines = new ArrayList<>();
        lines.add(Ansi.bold("有效定义 · " + text(d, "displayName")));
        lines.add("Agent      " + text(p, "agent"));
        lines.add("身份       " + text(p, "identity"));
        lines.add("查看者     " + viewer.format());
        if (p.has("tenantId")) lines.add("租户       " + text(p, "tenantId"));
        List<String> extensions = new ArrayList<>();
        p.get("extensions").forEach(e -> extensions.add(e.asString()));
        lines.add("扩展       " + (extensions.isEmpty() ? "无" : String.join("、", extensions)));
        if (p.has("resolvedTarget")) {
            List<String> ranges = new ArrayList<>();
            p.get("resolvedTarget").get("ranges").forEach(r -> ranges.add(r.asString()));
            lines.add("版本解析   " + String.join(" ∩ ", ranges) + " → " + text(p.get("resolvedTarget"), "version"));
        }
        JsonNode model = d.get("model");
        lines.add("模型档位   " + text(model, "profile") + "（" + LAYER_LABEL.get(text(model, "layer")) + "决定）");
        lines.add("写级别上限 " + text(d.get("writePolicy"), "maxLevel"));
        lines.add(heading("指令（按组装顺序）"));
        d.get("segments").forEach(s -> lines.add(renderSegment(s)));
        lines.add(heading("工具"));
        d.get("tools")
                .forEach(t -> lines.add(tag(text(t, "layer")) + " " + String.format("%-36s", text(t, "name")) + " "
                        + String.format("%-3s", t.has("writeLevel") ? text(t, "writeLevel") : "—") + " "
                        + Ansi.dim(text(t, "source"))));
        lines.add(heading("人工确认"));
        if (d.get("approvals").isEmpty()) lines.add("（无）");
        d.get("approvals")
                .forEach(a -> lines.add(tag(text(a, "layer")) + " " + text(a, "tool") + "  " + text(a, "policy")
                        + (a.has("when") ? "  当 " + text(a, "when") : "")));
        lines.add(heading("钩子"));
        if (d.get("hooks").isEmpty()) lines.add("（无）");
        d.get("hooks")
                .forEach(h -> lines.add(tag(text(h, "layer")) + " " + text(h, "event") + "  "
                        + (h.has("workflow") ? text(h, "workflow") : text(h, "skill"))
                        + (h.has("when") ? "  当 " + text(h, "when") : "")
                        + "  失败时" + ("isolate".equals(text(h, "failurePolicy")) ? "隔离" : "中止")));
        return String.join("\n", lines);
    }

    public static int run(List<String> args, Path assetsRoot, PrintStream out, PrintStream err) {
        String agent = null;
        String tenantId = null;
        String as = "tenant";
        boolean json = false;
        for (int i = 0; i < args.size(); i++) {
            switch (args.get(i)) {
                case "--tenant" -> tenantId = args.get(++i);
                case "--as" -> as = args.get(++i);
                case "--json" -> json = true;
                default -> agent = args.get(i);
            }
        }
        if (agent == null) {
            err.println(
                    "用法：pnpm assets:assemble <namespace/name> [--tenant t001] [--as tenant|vendor|platform] [--json]");
            return 2;
        }
        Viewer viewer = switch (as) {
            case "tenant" -> Viewer.tenant(tenantId == null ? "t001" : tenantId);
            case "vendor" -> Viewer.VENDOR;
            case "platform" -> Viewer.PLATFORM;
            default -> {
                err.println("--as 只能是 tenant、vendor 或 platform，收到 " + as);
                yield null;
            }
        };
        if (viewer == null) return 2;

        var loaded = RepoRegistry.load(assetsRoot);
        loaded.seed().failed().forEach(f -> err.println(Ansi.red("资产加载失败 " + f.file() + "：" + f.message())));
        var outcome = EffectiveAssembly.assemble(
                loaded.services().registry(), viewer, EffectiveAssembly.Request.of(agent, tenantId));
        loaded.services().db().close();
        if (!(outcome instanceof EffectiveAssembly.Outcome.Ok success)) {
            outcome.issues().forEach(i -> err.println(Ansi.red("[" + i.code() + "] " + i.message())));
            return 1;
        }
        out.println(
                json
                        ? Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(success.view())
                        : render(success.view(), viewer));
        success.issues().forEach(i -> out.println(Ansi.yellow("警告 [" + i.code() + "] " + i.message())));
        return 0;
    }

    public static void main(String[] args) {
        Ansi.quietLogs();
        System.exit(run(List.of(args), PlatformProperties.repoRoot().resolve("assets"), System.out, System.err));
    }
}
