package dev.eab.runtime;

import dev.eab.assembler.Conditions;
import dev.eab.assembler.EffectiveDefinition;
import dev.eab.assembler.EffectiveDefinition.SegmentKind;
import dev.eab.runtime.RunEvents.RunEvent;
import dev.eab.runtime.ToolGateway.ToolOutcome;
import dev.eab.runtime.semantic.SemanticService;
import dev.eab.spec.Json;
import dev.eab.spec.model.SemanticPackSpec.SemanticRule;
import dev.eab.tracing.SpanRecord.SpanKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 约束守卫（ADR 0010）：Agent 声明的约束如果引用了带检查条件的语义规则，某个工具返回后由这里按条件判断规则是否触发。
 * 触发时写一个守卫 span、推送 constraint 事件，并把规则和处置建议写进交给模型的工具结果。判断只依赖运行日志里记录的工具结果，
 * 重放时结果相同。每条规则在一次运行里只触发一次。
 */
final class ConstraintGuard {

    private final List<SemanticService.SourcedRule> active;
    private final Set<String> triggered = new HashSet<>();

    private ConstraintGuard(List<SemanticService.SourcedRule> active) {
        this.active = active;
    }

    /** 只按声明生效的旧入口（测试用）：规则视为来自标品层 */
    static ConstraintGuard of(EffectiveDefinition definition, List<SemanticRule> rules) {
        return ofSourced(
                definition, rules.stream().map(r -> new SemanticService.SourcedRule(r, null, false)).toList());
    }

    /**
     * Agent 声明了的约束按检查条件生效；租户层语义包扩展里带检查条件的规则对挂载了被扩展语义包的 Agent 一律生效，
     * 不需要逐个 Agent 声明（经验回流，ADR 0013）。
     */
    static ConstraintGuard ofSourced(EffectiveDefinition definition, List<SemanticService.SourcedRule> rules) {
        Set<String> declared = new HashSet<>();
        for (var segment : definition.segments()) {
            boolean constraint =
                    segment.kind() == SegmentKind.vendor_constraint || segment.kind() == SegmentKind.tenant_constraint;
            if (constraint && segment.label() != null) declared.add(segment.label());
        }
        return new ConstraintGuard(rules.stream()
                .filter(r -> r.rule().check() != null && (r.tenantLayer() || declared.contains(r.rule().id())))
                .toList());
    }

    /**
     * 工具返回后检查。
     *
     * @param replayKey 下一步的日志路径，日志里已有时说明在重放，不再推送事件
     * @return 这次触发的约束，没有时为 null
     */
    List<Map<String, Object>> after(Exec exec, String tool, ToolOutcome outcome, String replayKey) {
        if (!outcome.ok() || !(outcome.data() instanceof Map<?, ?> data)) return null;
        List<Map<String, Object>> notes = new ArrayList<>();
        for (SemanticService.SourcedRule sourced : active) {
            SemanticRule rule = sourced.rule();
            if (!rule.check().tool().equals(tool) || triggered.contains(rule.id())) continue;
            if (!matches(exec, rule, data)) continue;
            triggered.add(rule.id());
            exec.span("约束 · " + rule.id() + " 触发", SpanKind.guard, "constraint:" + rule.id())
                    .set("eab.guard.result", "triggered")
                    .set("eab.constraint.rule", rule.id())
                    .set("eab.constraint.severity", rule.severity())
                    .set("eab.constraint.tool", tool)
                    .set("eab.constraint.remedy", rule.remedy())
                    .set("eab.constraint.source", sourced.pack())
                    .end();
            exec.emit(
                    replayKey,
                    RunEvent.of(
                            "constraint",
                            exec.ctx().agent(),
                            "rule",
                            rule.id(),
                            "severity",
                            rule.severity(),
                            "remedy",
                            rule.remedy(),
                            "tool",
                            tool,
                            "source",
                            sourced.pack()));
            notes.add(Map.of(
                    "rule", rule.id(),
                    "severity", rule.severity(),
                    "remedy", rule.remedy(),
                    "instruction", "平台约束 " + rule.id() + " 已触发，回答必须先说明这一点"));
        }
        return notes.isEmpty() ? null : notes;
    }

    private static boolean matches(Exec exec, SemanticRule rule, Map<?, ?> data) {
        try {
            return Conditions.test(rule.check().when(), Map.of("output", data));
        } catch (RuntimeException e) {
            // 条件的语法在组装时已检查；数据形状不符时按未触发处理，并在 span 里记下原因
            exec.span("约束 · " + rule.id(), SpanKind.guard, "constraint:" + rule.id())
                    .set("eab.constraint.rule", rule.id())
                    .fail("检查条件求值失败：" + e.getMessage());
            return false;
        }
    }

    /** 把触发的约束写进工具结果的 JSON（platformConstraints 字段），结果不是 JSON 对象时包一层 */
    static String annotate(String content, List<Map<String, Object>> notes) {
        Map<String, Object> merged = new LinkedHashMap<>();
        try {
            Object parsed = Json.MAPPER.readValue(content, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> merged.put(String.valueOf(k), v));
            } else {
                merged.put("result", parsed);
            }
        } catch (RuntimeException e) {
            merged.put("result", content);
        }
        merged.put("platformConstraints", notes);
        return Json.MAPPER.writeValueAsString(merged);
    }
}
