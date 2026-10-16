package dev.eab.runtime.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 输入输出内容过滤（主提示词 8.2 节）。这是提示词之外的确定性检查，规则保持简单、可读：输入侧拦截典型的提示注入特征；
 * 输出侧拦截复述系统指令的内容。
 */
public final class ContentFilters {

    private ContentFilters() {}

    private record InjectionPattern(String id, Pattern pattern) {}

    private static final List<InjectionPattern> INJECTION_PATTERNS = List.of(
            new InjectionPattern(
                    "ignore-instructions",
                    Pattern.compile("忽略(?:掉)?(?:你)?(?:之前|以上|前面|上面|所有)(?:的)?(?:所有)?(?:指令|提示|规则|设定)")),
            new InjectionPattern(
                    "ignore-instructions-en",
                    Pattern.compile(
                            "ignore\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|prior|above)\\s+(?:instructions|prompts?|rules)",
                            Pattern.CASE_INSENSITIVE)),
            new InjectionPattern(
                    "reveal-prompt",
                    Pattern.compile(
                            "(?:输出|打印|告诉我|显示|复述|泄露)(?:一下)?(?:你的)?(?:完整的?)?(?:系统提示词|系统指令|原始指令|system\\s*prompt)",
                            Pattern.CASE_INSENSITIVE)),
            new InjectionPattern(
                    "reveal-prompt-en",
                    Pattern.compile(
                            "(?:reveal|print|show|repeat)\\s+(?:your\\s+)?(?:system\\s+prompt|instructions)",
                            Pattern.CASE_INSENSITIVE)),
            new InjectionPattern("jailbreak", Pattern.compile("(?:你现在是|扮演).{0,20}(?:不受|没有|无视).{0,6}(?:限制|约束|规则)")));

    /** @param ruleId 命中的规则，未拦截时为 null */
    public record Verdict(boolean blocked, String ruleId) {}

    public static Verdict checkInput(String text) {
        if (text == null) return new Verdict(false, null);
        for (InjectionPattern p : INJECTION_PATTERNS) {
            if (p.pattern().matcher(text).find()) return new Verdict(true, p.id());
        }
        return new Verdict(false, null);
    }

    public static final String INPUT_BLOCKED_REPLY = "这个请求包含试图改变或获取系统指令的内容，已被内容过滤拦截。请直接描述你要处理的业务问题。";

    /** 系统提示词里的片段标记，出现在输出里说明模型在复述系统提示词 */
    private static final Pattern SEGMENT_MARKER =
            Pattern.compile("<!--\\s*(?:platform-rule|vendor-instructions|vendor-context|vendor-constraint)[^>]*-->");

    /** 判断“复述”的最短行长：太短的行（如“1.”）在正常回答里也会出现 */
    static final int MIN_ECHO_LENGTH = 16;

    public static final String MASKED_ECHO = "［已遮蔽：内部指令］";

    public record ConfidentialText(String id, String text) {}

    /**
     * @param leakedSegments 被复述的保密片段编号
     */
    public record OutputResult(String text, List<String> leakedSegments, int markersRemoved) {}

    /**
     * 输出过滤：去掉片段标记；回复里逐字出现保密片段（标品指令、平台规则原文）中足够长的行时，把这些行遮蔽并报告片段编号，
     * 由监督层产生告警（ADR 0004）。
     */
    public static OutputResult filterOutput(String text, List<ConfidentialText> confidential) {
        var matcher = SEGMENT_MARKER.matcher(text == null ? "" : text);
        int removed = 0;
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            removed++;
            matcher.appendReplacement(out, "");
        }
        matcher.appendTail(out);
        String result = out.toString();
        Set<String> leaked = new LinkedHashSet<>();
        for (ConfidentialText segment : confidential) {
            for (String raw : segment.text().split("\n")) {
                String line = raw.trim();
                if (line.length() < MIN_ECHO_LENGTH || !result.contains(line)) continue;
                leaked.add(segment.id());
                result = result.replace(line, MASKED_ECHO);
            }
        }
        return new OutputResult(result, new ArrayList<>(leaked), removed);
    }
}
