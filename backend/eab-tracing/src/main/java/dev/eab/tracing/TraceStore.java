package dev.eab.tracing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** span 的存储。平台用 SQLite 实现，测试和命令行用内存实现 */
public interface TraceStore {

    /**
     * 写入一个已结束的 span。同一 spanId 已经以最终状态存在时忽略；已存在但状态为 suspended 时，用新状态覆盖并保留原开始时间。
     */
    void upsert(SpanRecord span);

    /** 按开始时间排序 */
    List<SpanRecord> list(String traceId);

    /** 内存存储 */
    final class InMemory implements TraceStore {
        private final Map<String, SpanRecord> spans = new ConcurrentHashMap<>();

        @Override
        public void upsert(SpanRecord span) {
            spans.compute(span.traceId() + ":" + span.spanId(), (key, existing) -> {
                SpanRecord merged = SpanRecord.merge(existing, span);
                return merged == null ? existing : merged;
            });
        }

        @Override
        public List<SpanRecord> list(String traceId) {
            List<SpanRecord> result = new ArrayList<>();
            for (SpanRecord span : spans.values()) if (span.traceId().equals(traceId)) result.add(span);
            result.sort(Comparator.comparing(SpanRecord::startTime));
            return result;
        }
    }
}
