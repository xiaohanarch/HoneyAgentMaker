package dev.eab.policy;

import dev.eab.assembler.Conditions;
import dev.eab.spec.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * 鉴权点 1 至 3 的策略引擎（主提示词 7.3、7.4 节）。规则是 JSON，条件用 CEL；
 * 引擎是纯函数，输出结构化的裁决，由调用方写入追踪和审计。
 */
public final class PolicyEngine {

    private PolicyEngine() {}

    /** 平台负责的三个鉴权点；第四处在 DemoERP 的 BO API */
    public static final List<String> CHECKPOINTS = List.of("entry", "handoff", "tool");

    public static final Map<String, String> CHECKPOINT_LABELS = Map.of(
            "entry", "鉴权点 1 · 入口",
            "handoff", "鉴权点 2 · Agent 交接",
            "tool", "鉴权点 3 · 工具与 MCP 网关",
            "bo-api", "鉴权点 4 · BO API");

    /** deny 拒绝；approve 需要有权限的人审批；confirm 需要发起人确认。判定优先级 deny > approve > confirm > allow */
    public static final List<String> RULE_EFFECTS = List.of("deny", "approve", "confirm");

    /** @param when CEL 条件，变量为 input；条件为真时规则命中 */
    public record PolicyRule(
            String id, String checkpoint, String description, String effect, String when, String reason) {}

    public record PolicyRuleSet(String version, List<PolicyRule> rules) {}

    public record Requirement(String ruleId, String effect, String reason) {}

    /**
     * 每一次裁决的结构化记录，写入追踪和审计。
     *
     * @param ruleId 决定结果的规则；allow 时为 null
     * @param requirements 需要确认或审批时列出全部命中的规则
     * @param facts 判定依据的事实（不含敏感内容），便于在追踪里回放
     */
    public record Decision(
            String checkpoint,
            String outcome,
            String ruleId,
            String reason,
            List<Requirement> requirements,
            String rulesVersion,
            String at,
            Map<String, Object> facts) {}

    public static final class PolicyRuleException extends RuntimeException {
        public PolicyRuleException(String message) {
            super(message);
        }
    }

    private static final Pattern RULE_ID = Pattern.compile("^[A-Z]+-\\d{3}$");

    /** 加载规则集：结构校验 + 每条条件都必须能通过 CEL 语法检查，不合格的规则集拒绝加载 */
    public static PolicyRuleSet load(JsonNode raw) {
        PolicyRuleSet set;
        try {
            set = Json.MAPPER.treeToValue(raw, PolicyRuleSet.class);
        } catch (RuntimeException e) {
            throw new PolicyRuleException("策略规则集不合法：" + e.getMessage());
        }
        if (set.version() == null || set.rules() == null || set.rules().isEmpty()) {
            throw new PolicyRuleException("策略规则集不合法：缺少 version 或 rules");
        }
        Set<String> ids = new HashSet<>();
        for (PolicyRule rule : set.rules()) {
            if (rule.id() == null || !RULE_ID.matcher(rule.id()).matches()) {
                throw new PolicyRuleException("策略规则集不合法：规则编号格式应为 前缀-三位数字，例如 TOOL-005");
            }
            if (!CHECKPOINTS.contains(rule.checkpoint())
                    || !RULE_EFFECTS.contains(rule.effect())
                    || isBlank(rule.description())
                    || isBlank(rule.when())
                    || isBlank(rule.reason())) {
                throw new PolicyRuleException("策略规则集不合法：规则 " + rule.id() + " 的字段不完整或取值不对");
            }
            if (!ids.add(rule.id())) throw new PolicyRuleException("规则编号 " + rule.id() + " 重复");
            var check = Conditions.check(Conditions.policyEnvironment(), rule.when());
            if (!check.valid()) throw new PolicyRuleException("规则 " + rule.id() + " 的条件无效：" + check.reason());
        }
        return set;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    /** 平台内置的默认规则集，启动时校验，不合格直接报错 */
    public static final PolicyRuleSet DEFAULT_RULES = loadDefault();

    private static PolicyRuleSet loadDefault() {
        try (InputStream in = PolicyEngine.class.getResourceAsStream("/policy/default.json")) {
            if (in == null) throw new IllegalStateException("缺少默认策略规则文件 policy/default.json");
            return load(Json.MAPPER.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    /** 规则原因里的 {{a.b}} 用事实填充，便于给出具体说明 */
    static String renderReason(String template, Map<String, Object> input) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = input;
            for (String key : m.group(1).split("\\.")) {
                value = value instanceof Map<?, ?> map ? map.get(key) : null;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static Decision evaluate(PolicyRuleSet ruleSet, String checkpoint, Map<String, Object> input) {
        return evaluate(ruleSet, checkpoint, input, Clock.systemUTC().instant());
    }

    /**
     * 对一个鉴权点做判定（纯函数）：依次判断该鉴权点的规则；命中 deny 立即拒绝；
     * 命中 approve、confirm 的累积起来，结果取最严的一类；条件求值出错一律拒绝（失败即拒绝）；
     * 没有命中任何规则时允许。
     */
    public static Decision evaluate(PolicyRuleSet ruleSet, String checkpoint, Map<String, Object> input, Instant now) {
        String at = ISO.format(now);
        List<Requirement> requirements = new ArrayList<>();
        for (PolicyRule rule : ruleSet.rules()) {
            if (!rule.checkpoint().equals(checkpoint)) continue;
            boolean hit;
            try {
                Object value = Conditions.evaluate(rule.when(), Map.of("input", input));
                if (!(value instanceof Boolean b)) throw new PolicyRuleException("规则 " + rule.id() + " 的条件结果不是布尔值");
                hit = b;
            } catch (RuntimeException e) {
                String detail = e.getMessage() == null
                        ? ""
                        : e.getMessage().lines().findFirst().orElse("");
                return new Decision(
                        checkpoint,
                        "deny",
                        rule.id(),
                        "策略规则 " + rule.id() + " 求值失败，按失败即拒绝处理：" + detail,
                        List.of(),
                        ruleSet.version(),
                        at,
                        input);
            }
            if (!hit) continue;
            String reason = renderReason(rule.reason(), input);
            if (rule.effect().equals("deny")) {
                return new Decision(checkpoint, "deny", rule.id(), reason, List.of(), ruleSet.version(), at, input);
            }
            requirements.add(new Requirement(rule.id(), rule.effect(), reason));
        }
        Requirement approval = requirements.stream()
                .filter(r -> r.effect().equals("approve"))
                .findFirst()
                .orElse(null);
        Requirement first = approval != null ? approval : requirements.isEmpty() ? null : requirements.getFirst();
        if (first == null) {
            return new Decision(checkpoint, "allow", null, "没有命中限制规则", requirements, ruleSet.version(), at, input);
        }
        return new Decision(
                checkpoint,
                approval == null ? "confirm" : "approve",
                first.ruleId(),
                String.join("；", requirements.stream().map(Requirement::reason).toList()),
                requirements,
                ruleSet.version(),
                at,
                input);
    }
}
