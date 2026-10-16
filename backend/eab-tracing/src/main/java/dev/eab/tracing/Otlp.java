package dev.eab.tracing;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 转成 OTLP/HTTP JSON（ExportTraceServiceRequest）。系统提示词片段不导出，只导出片段编号：外部采集端不属于任何一层资产的查看者，
 * 标品指令原文不应离开平台。
 */
public final class Otlp {

    private Otlp() {}

    static Map<String, Object> anyValue(Object value, Function<Object, String> json) {
        return switch (value) {
            case String s -> Map.of("stringValue", s);
            case Boolean b -> Map.of("boolValue", b);
            case Integer i -> Map.of("intValue", String.valueOf(i));
            case Long l -> Map.of("intValue", String.valueOf(l));
            case Number n ->
                n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 1e15
                        ? Map.of("intValue", String.valueOf(n.longValue()))
                        : Map.of("doubleValue", n.doubleValue());
            default -> Map.of("stringValue", json.apply(value));
        };
    }

    static String nanos(String iso) {
        Instant instant = Instant.parse(iso);
        return BigInteger.valueOf(instant.getEpochSecond())
                .multiply(BigInteger.valueOf(1_000_000_000L))
                .add(BigInteger.valueOf(instant.getNano()))
                .toString();
    }

    private static Map<String, Object> attribute(String key, Map<String, Object> value) {
        Map<String, Object> attribute = new LinkedHashMap<>();
        attribute.put("key", key);
        attribute.put("value", value);
        return attribute;
    }

    /**
     * @param json 把非基本类型的属性值序列化成 JSON 字符串
     */
    public static Map<String, Object> export(
            List<SpanRecord> spans, String serviceName, Function<Object, String> json) {
        List<Map<String, Object>> otlpSpans = new ArrayList<>();
        for (SpanRecord span : spans) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("traceId", span.traceId());
            s.put("spanId", span.spanId());
            if (span.parentSpanId() != null) s.put("parentSpanId", span.parentSpanId());
            s.put("name", span.name());
            s.put("kind", 1);
            s.put("startTimeUnixNano", nanos(span.startTime()));
            s.put("endTimeUnixNano", nanos(span.endTime()));
            List<Map<String, Object>> attributes = new ArrayList<>();
            attributes.add(
                    attribute("eab.span.kind", Map.of("stringValue", span.kind().name())));
            if (span.tenantId() != null)
                attributes.add(attribute("eab.tenant.id", Map.of("stringValue", span.tenantId())));
            if (span.promptSegments() != null) {
                String ids = String.join(
                        ",",
                        span.promptSegments().stream()
                                .map(SpanRecord.PromptSegment::id)
                                .toList());
                attributes.add(attribute("eab.prompt.segment_ids", Map.of("stringValue", ids)));
            }
            span.attributes().forEach((key, value) -> attributes.add(attribute(key, anyValue(value, json))));
            s.put("attributes", attributes);
            String message = span.statusMessage() == null ? "" : span.statusMessage();
            int code = switch (span.status()) {
                case error -> 2;
                case ok -> 1;
                case suspended -> 0;
            };
            s.put("status", Map.of("code", code, "message", message));
            otlpSpans.add(s);
        }
        Map<String, Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", Map.of("name", "dev.eab.tracing", "version", "0.1.0"));
        scopeSpans.put("spans", otlpSpans);
        Map<String, Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put(
                "resource",
                Map.of("attributes", List.of(attribute("service.name", Map.of("stringValue", serviceName)))));
        resourceSpans.put("scopeSpans", List.of(scopeSpans));
        return Map.of("resourceSpans", List.of(resourceSpans));
    }
}
