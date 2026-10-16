package dev.eab.tracing;

import dev.eab.tracing.SpanRecord.PromptSegment;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.SpanRecord.SpanStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次运行一个 Tracer。父子关系由调用方显式传入，不依赖调用栈，并行分支里的 span 也能挂到正确的父节点上。
 *
 * <p>spanId 由 traceId 和 span 在运行里的结构化路径（key，例如 turn:2/tool:call_1）派生：运行从人工确认处恢复、
 * 重放到同一位置时得到同一个 spanId，挂起时以 suspended 落库的 span 会被最终状态覆盖（ADR 0008）。
 */
public final class Tracer {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String traceId;
    private final TraceStore store;
    private final String tenantId;
    private final Clock clock;
    private final Set<Span> open = ConcurrentHashMap.newKeySet();

    public Tracer(String traceId, TraceStore store, String tenantId, Clock clock) {
        this.traceId = traceId == null ? newTraceId() : traceId;
        this.store = store;
        this.tenantId = tenantId;
        this.clock = clock;
    }

    /** OTLP 的 traceId 是 32 位十六进制 */
    public static String newTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String spanIdOf(String traceId, String key) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256").digest((traceId + ":" + key).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String traceId() {
        return traceId;
    }

    public String tenantId() {
        return tenantId;
    }

    String now() {
        return ISO.format(Instant.now(clock));
    }

    /**
     * @param parent 父 span，根 span 为 null
     * @param key span 在本次运行里的结构化路径，同一次运行内唯一
     */
    public Span start(String name, SpanKind kind, Span parent, String key, Map<String, Object> attributes) {
        Span span = new Span(
                this, spanIdOf(traceId, key), key, parent == null ? null : parent.spanId(), name, kind, attributes);
        open.add(span);
        return span;
    }

    /** 挂起信号：运行等待人工确认时抛出，不算错误，span 保持打开，由 {@link #suspendOpenSpans} 以 suspended 落库 */
    public interface Suspension {}

    @FunctionalInterface
    public interface SpanBody<T> {
        T run(Span span) throws Exception;
    }

    /** 在 span 内执行 body；body 抛错时 span 以 error 结束并继续抛出，挂起信号除外 */
    public <T> T within(
            String name, SpanKind kind, Span parent, String key, Map<String, Object> attributes, SpanBody<T> body)
            throws Exception {
        Span span = start(name, kind, parent, key, attributes);
        try {
            T result = body.run(span);
            span.end();
            return result;
        } catch (Exception e) {
            if (!(e instanceof Suspension)) span.fail(e.getMessage());
            throw e;
        }
    }

    /** 把全部未结束的 span 以 suspended 落库，但不标记为结束（运行挂起等待确认时调用） */
    public void suspendOpenSpans() {
        for (Span span : open) span.flushSuspended();
    }

    void finish(
            Span span,
            SpanStatus status,
            String message,
            Map<String, Object> attributes,
            List<PromptSegment> segments) {
        open.remove(span);
        write(span, status, message, attributes, segments);
    }

    void write(
            Span span,
            SpanStatus status,
            String message,
            Map<String, Object> attributes,
            List<PromptSegment> segments) {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("eab.span.key", span.key());
        all.putAll(attributes);
        store.upsert(new SpanRecord(
                traceId,
                span.spanId(),
                span.parentSpanId(),
                span.name(),
                span.kind(),
                span.startTime(),
                now(),
                status,
                message,
                all,
                segments,
                tenantId));
    }

    /** 一个进行中的 span。end 之后写入存储，重复 end 无效 */
    public static final class Span {
        private final Tracer tracer;
        private final String spanId;
        private final String key;
        private final String parentSpanId;
        private final String name;
        private final SpanKind kind;
        private final String startTime;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private List<PromptSegment> segments;
        private volatile boolean ended;

        Span(
                Tracer tracer,
                String spanId,
                String key,
                String parentSpanId,
                String name,
                SpanKind kind,
                Map<String, Object> attributes) {
            this.tracer = tracer;
            this.spanId = spanId;
            this.key = key;
            this.parentSpanId = parentSpanId;
            this.name = name;
            this.kind = kind;
            this.startTime = tracer.now();
            if (attributes != null) attributes.forEach(this::set);
        }

        public String spanId() {
            return spanId;
        }

        public String key() {
            return key;
        }

        public String parentSpanId() {
            return parentSpanId;
        }

        public String name() {
            return name;
        }

        public SpanKind kind() {
            return kind;
        }

        public String startTime() {
            return startTime;
        }

        public Tracer tracer() {
            return tracer;
        }

        public boolean ended() {
            return ended;
        }

        /** 子 span 的 key：本 span 的 key 加一段路径 */
        public String childKey(String segment) {
            return key + "/" + segment;
        }

        public Span child(String name, SpanKind kind, String keySegment, Map<String, Object> attributes) {
            return tracer.start(name, kind, this, childKey(keySegment), attributes);
        }

        /** null 值不记录 */
        public Span set(String name, Object value) {
            if (value != null) attributes.put(name, value);
            return this;
        }

        public Span promptSegments(List<PromptSegment> value) {
            this.segments = List.copyOf(value);
            return this;
        }

        public void end() {
            end(SpanStatus.ok, null);
        }

        public void fail(String message) {
            end(SpanStatus.error, message);
        }

        public synchronized void end(SpanStatus status, String message) {
            if (ended) return;
            ended = true;
            tracer.finish(this, status, message, new LinkedHashMap<>(attributes), segments);
        }

        synchronized void flushSuspended() {
            if (ended) return;
            tracer.write(this, SpanStatus.suspended, null, new LinkedHashMap<>(attributes), segments);
        }
    }
}
