package dev.eab.runtime;

import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.Tracer.Span;

/**
 * 一次执行所在的位置：运行上下文、运行日志、事件出口、父 span。key 是结构化路径的前缀，日志记录与 spanId 都由它派生。
 *
 * @param confirmed 外层已经取得用户确认（例如技能在调用前已确认），内部的确认点视为已满足，不再重复询问
 */
public record Exec(RunContext ctx, Journal journal, RunEvents.Sink sink, Span span, String key, boolean confirmed) {

    /** 在当前位置下开一个子 span，span 的路径与运行日志的路径一致：key/segment */
    public Span span(String name, SpanKind kind, String segment) {
        return span.tracer().start(name, kind, span, key + "/" + segment, java.util.Map.of());
    }

    public Exec under(Span child, String segment) {
        return new Exec(ctx, journal, sink, child, key + "/" + segment, confirmed);
    }

    public Exec withConfirmed(boolean value) {
        return new Exec(ctx, journal, sink, span, key, value);
    }

    public Exec withContext(RunContext value) {
        return new Exec(value, journal, sink, span, key, confirmed);
    }

    /** 重放时日志里已有的步骤不再推送事件，调用方已经看过 */
    public void emit(String journalKey, RunEvents.RunEvent event) {
        if (!journal.has(journalKey)) sink.accept(event);
    }

    public void emit(RunEvents.RunEvent event) {
        sink.accept(event);
    }
}
