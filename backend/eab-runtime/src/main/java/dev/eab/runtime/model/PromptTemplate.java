package dev.eab.runtime.model;

import dev.eab.assembler.EffectiveDefinition.Segment;
import dev.eab.tracing.SpanRecord.PromptSegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 提示模板（模型处理管道的第一个环节）：有效定义的片段（每段带来源标记）、用户上下文、长期记忆、检索到的知识、结构化结果格式。
 * 返回拼好的系统提示词，以及写进追踪的片段数组：追踪只保存片段，读取时按查看者裁剪。
 */
public final class PromptTemplate {

    private PromptTemplate() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param delegation 受托自主运行时的委托说明，否则为 null
     */
    public record UserContext(
            String userId,
            String userName,
            String tenantId,
            String tenantName,
            List<String> roles,
            String dataScope,
            String today,
            String delegation) {}

    public record KnowledgeSnippet(String id, String source, String text) {}

    public record OutputField(String name, String type, String description, List<String> enumValues) {}

    public record Input(
            List<Segment> segments, UserContext user, List<String> memory, List<OutputField> outputFields) {}

    public record Prompt(String system, List<PromptSegment> segments) {}

    public static Prompt build(Input input, List<KnowledgeSnippet> knowledge) {
        List<PromptSegment> generated = new ArrayList<>();
        UserContext user = input.user();
        List<String> userLines = new ArrayList<>(List.of(
                "## 当前用户",
                user.userName() + "（" + user.userId() + "），租户 " + user.tenantName() + "，角色 "
                        + String.join("、", user.roles()),
                "数据权限：" + user.dataScope(),
                "今天是 " + user.today()));
        if (user.delegation() != null) userLines.add("本次为受托自主运行：" + user.delegation());
        generated.add(
                new PromptSegment("ctx-user", "user-context", "platform/runtime", false, String.join("\n", userLines)));
        if (!input.memory().isEmpty()) {
            List<String> lines = new ArrayList<>(List.of("## 用户的长期记忆"));
            input.memory().forEach(m -> lines.add("- " + m));
            generated.add(
                    new PromptSegment("ctx-memory", "memory", "platform/memory", false, String.join("\n", lines)));
        }
        if (!knowledge.isEmpty()) {
            List<String> lines = new ArrayList<>(List.of("## 相关的业务口径与规则"));
            knowledge.forEach(k -> lines.add("- " + k.text() + "（来源：" + k.source() + "）"));
            generated.add(new PromptSegment(
                    "ctx-knowledge", "knowledge", "platform/retrieval", false, String.join("\n", lines)));
        }
        if (!input.outputFields().isEmpty()) {
            List<String> lines = new ArrayList<>(List.of("## 结构化结果", "回答的最后附一个 ```json 代码块，包含以下字段："));
            for (OutputField f : input.outputFields()) {
                String enums = f.enumValues() == null ? "" : "，取值 " + String.join("/", f.enumValues());
                String description = f.description() == null ? "" : "：" + f.description();
                lines.add("- " + f.name() + "（" + f.type() + enums + "）" + description);
            }
            generated.add(new PromptSegment(
                    "ctx-output", "output-format", "platform/runtime", false, String.join("\n", lines)));
        }
        List<PromptSegment> segments = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (Segment s : input.segments()) {
            segments.add(new PromptSegment(s.id(), s.kind().value(), s.source(), s.confidential(), s.text()));
            parts.add("<!-- " + s.kind().value() + " " + s.source() + " " + s.field() + " -->\n"
                    + s.text().strip());
        }
        segments.addAll(generated);
        generated.forEach(g -> parts.add(g.text()));
        return new Prompt(String.join("\n\n", parts), segments);
    }

    /** @param output 解析出的结构化结果，没有时为 null */
    public record Extracted(String text, Map<String, Object> output) {}

    private static final Pattern FENCE = Pattern.compile("```json\\s*([\\s\\S]*?)```\\s*$");

    /** 从回复里取出最后一个 ```json 代码块作为结构化结果，并从展示文本里去掉它 */
    public static Extracted extractOutput(String text) {
        String trimmed = text == null ? "" : text.stripTrailing();
        Matcher match = FENCE.matcher(trimmed);
        int start = -1;
        String body = null;
        while (match.find()) {
            start = match.start();
            body = match.group(1);
        }
        if (body == null) return new Extracted(text, null);
        try {
            JsonNode parsed = JSON.readTree(body);
            if (parsed == null || !parsed.isObject()) return new Extracted(text, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = JSON.convertValue(parsed, LinkedHashMap.class);
            return new Extracted(trimmed.substring(0, start).stripTrailing(), output);
        } catch (RuntimeException e) {
            return new Extracted(text, null);
        }
    }
}
