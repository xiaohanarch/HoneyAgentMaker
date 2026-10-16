package dev.eab.tracing;

import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** 追踪的查看规则：谁能看、看到什么，以及组织成树 */
public final class TraceView {

    private TraceView() {}

    /**
     * 能否查看这条追踪：平台运维可以看全部；租户只能看本租户的运行；厂商研发看不到任何租户的运行数据（主提示词 5.2 节）。
     */
    public static boolean canView(Viewer viewer, List<SpanRecord> spans) {
        return switch (viewer) {
            case Viewer.Platform p -> true;
            case Viewer.Vendor v -> false;
            case Viewer.Tenant t ->
                !spans.isEmpty() && spans.stream().allMatch(s -> t.tenantId().equals(s.tenantId()));
        };
    }

    /**
     * 按查看者裁剪（ADR 0004）：租户视角下，模型调用 span 里的保密片段换成不可展开的占位块，平台规则只保留规则名。
     * 片段之外的属性在写入时就不含指令原文。返回 JSON 友好的 Map，保密片段的 text 是 {@link Visibility.Redacted}。
     */
    public static List<Map<String, Object>> view(List<SpanRecord> spans, Viewer viewer, UnaryOperator<String> digest) {
        boolean tenant = viewer instanceof Viewer.Tenant;
        List<Map<String, Object>> result = new ArrayList<>();
        for (SpanRecord span : spans) result.add(toMap(span, tenant, digest));
        return result;
    }

    private static Map<String, Object> toMap(SpanRecord span, boolean tenant, UnaryOperator<String> digest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traceId", span.traceId());
        map.put("spanId", span.spanId());
        map.put("parentSpanId", span.parentSpanId());
        map.put("name", span.name());
        map.put("kind", span.kind().name());
        map.put("startTime", span.startTime());
        map.put("endTime", span.endTime());
        map.put("status", span.status().name());
        if (span.statusMessage() != null) map.put("statusMessage", span.statusMessage());
        map.put("attributes", span.attributes());
        if (span.tenantId() != null) map.put("tenantId", span.tenantId());
        if (span.promptSegments() != null) {
            List<Map<String, Object>> segments = new ArrayList<>();
            for (SpanRecord.PromptSegment segment : span.promptSegments()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", segment.id());
                s.put("kind", segment.kind());
                s.put("source", segment.source());
                s.put("confidential", segment.confidential());
                s.put(
                        "text",
                        tenant && segment.confidential()
                                ? Visibility.redact(
                                        segment.text(),
                                        segment.kind().equals("platform-rule")
                                                ? Visibility.PLATFORM_RULE_LABEL
                                                : Visibility.VENDOR_INSTRUCTION_LABEL,
                                        digest)
                                : segment.text());
                segments.add(s);
            }
            map.put("promptSegments", segments);
        }
        return map;
    }

    /** 树节点 */
    public record Node(SpanRecord span, List<Node> children) {}

    /** 把扁平的 span 列表组织成树，同级按开始时间排序；父节点缺失的 span 作为根 */
    public static List<Node> tree(List<SpanRecord> spans) {
        Map<String, List<SpanRecord>> byParent = new HashMap<>();
        Map<String, SpanRecord> byId = new HashMap<>();
        for (SpanRecord span : spans) byId.put(span.spanId(), span);
        List<SpanRecord> roots = new ArrayList<>();
        for (SpanRecord span : spans) {
            if (span.parentSpanId() == null || !byId.containsKey(span.parentSpanId())) roots.add(span);
            else
                byParent.computeIfAbsent(span.parentSpanId(), k -> new ArrayList<>())
                        .add(span);
        }
        return build(roots, byParent);
    }

    private static List<Node> build(List<SpanRecord> level, Map<String, List<SpanRecord>> byParent) {
        List<SpanRecord> sorted = new ArrayList<>(level);
        sorted.sort(Comparator.comparing(SpanRecord::startTime));
        List<Node> nodes = new ArrayList<>();
        for (SpanRecord span : sorted) {
            nodes.add(new Node(span, build(byParent.getOrDefault(span.spanId(), List.of()), byParent)));
        }
        return nodes;
    }
}
