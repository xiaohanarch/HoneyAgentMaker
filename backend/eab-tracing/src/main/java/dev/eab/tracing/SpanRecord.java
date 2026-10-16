package dev.eab.tracing;

import java.util.List;
import java.util.Map;

/**
 * 一个已结束（或已挂起）的 span，字段与 OpenTelemetry 的 Span 对齐：traceId、spanId、parentSpanId、名称、种类、起止时间、
 * 状态、属性。属性名尽量沿用 OpenTelemetry 语义约定，模型调用用 gen_ai.*，工具调用用 eab.tool.*。
 *
 * @param parentSpanId 根 span 为 null
 * @param attributes 值只能是 JSON 能表示的类型
 * @param promptSegments 仅模型调用 span：系统提示词按片段保存，读取时按查看者裁剪（ADR 0004、0008）
 * @param tenantId 本 span 所属的租户，用于按查看者过滤；平台级运行为 null
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        SpanKind kind,
        String startTime,
        String endTime,
        SpanStatus status,
        String statusMessage,
        Map<String, Object> attributes,
        List<PromptSegment> promptSegments,
        String tenantId) {

    /** 平台自定义的 span 种类，决定追踪树里的图标和颜色（主提示词 8.1.1 节） */
    public enum SpanKind {
        run,
        routing,
        assemble,
        pipeline,
        model,
        policy,
        tool,
        skill,
        workflow,
        agent,
        guard,
        hook,
        confirmation,
        memory
    }

    /**
     * ok、error 与 OpenTelemetry 的状态码对应；suspended 是平台扩展：运行等待人工确认时，尚未结束的 span 先以 suspended
     * 落库，恢复后再写入最终状态。
     */
    public enum SpanStatus {
        ok,
        error,
        suspended
    }

    /**
     * 系统提示词的一个片段。
     *
     * @param kind 片段来源类别，例如 vendor-instructions、platform-rule
     * @param confidential 对租户保密：标品指令原文、平台规则原文
     */
    public record PromptSegment(String id, String kind, String source, boolean confidential, String text) {}

    /** 按存储约定合并：已有最终状态时忽略，已有 suspended 时覆盖并保留原开始时间 */
    public static SpanRecord merge(SpanRecord existing, SpanRecord incoming) {
        if (existing == null) return incoming;
        if (existing.status() != SpanStatus.suspended) return null;
        return new SpanRecord(
                incoming.traceId(),
                incoming.spanId(),
                incoming.parentSpanId(),
                incoming.name(),
                incoming.kind(),
                existing.startTime(),
                incoming.endTime(),
                incoming.status(),
                incoming.statusMessage(),
                incoming.attributes(),
                incoming.promptSegments(),
                incoming.tenantId());
    }
}
