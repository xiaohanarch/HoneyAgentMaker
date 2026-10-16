package dev.eab.tracing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import dev.eab.tracing.SpanRecord.PromptSegment;
import dev.eab.tracing.SpanRecord.SpanKind;
import dev.eab.tracing.SpanRecord.SpanStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TracingTest {

    static final String VENDOR_TEXT = "你是供应商绩效分析专家。这是标品指令原文";

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(String iso) {
            now = Instant.parse(iso);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class Suspend extends Exception implements Tracer.Suspension {
        Suspend() {
            super("等待确认");
        }
    }

    @Nested
    @DisplayName("Tracer")
    class Tracing {

        @Test
        @DisplayName("按显式父节点组织成树，并记录种类、状态和属性")
        void tree() throws Exception {
            var store = new TraceStore.InMemory();
            var tracer = new Tracer("a".repeat(32), store, "t001", Clock.systemUTC());
            var root = tracer.start("运行", SpanKind.run, null, "run", Map.of());
            tracer.within("模型调用", SpanKind.model, root, "turn:1/model", Map.of(), span -> "ok");
            assertThatThrownBy(() -> tracer.within("工具调用", SpanKind.tool, root, "turn:1/tool:c1", Map.of(), span -> {
                        throw new IllegalStateException("接口超时");
                    }))
                    .hasMessage("接口超时");
            root.set("eab.agent", "vendor.pur/x").end();

            var tree = TraceView.tree(store.list(tracer.traceId()));
            assertThat(tree).hasSize(1);
            assertThat(tree.getFirst().span().kind()).isEqualTo(SpanKind.run);
            assertThat(tree.getFirst().children().stream().map(n -> n.span().kind()))
                    .containsExactlyInAnyOrder(SpanKind.model, SpanKind.tool);
            var tool = tree.getFirst().children().stream()
                    .filter(n -> n.span().kind() == SpanKind.tool)
                    .findFirst()
                    .orElseThrow()
                    .span();
            assertThat(tool.status()).isEqualTo(SpanStatus.error);
            assertThat(tool.statusMessage()).isEqualTo("接口超时");
            assertThat(tree.getFirst().span().attributes()).containsEntry("eab.agent", "vendor.pur/x");
        }

        @Test
        @DisplayName("同一路径得到同一个 spanId；挂起的 span 恢复后被最终状态覆盖，开始时间保留")
        void suspendAndResume() throws Exception {
            var store = new TraceStore.InMemory();
            var clock = new MutableClock("2026-10-15T08:00:00Z");
            String traceId = "b".repeat(32);
            var first = new Tracer(traceId, store, null, clock);
            var root = first.start("运行", SpanKind.run, null, "run", Map.of());
            first.start("查询", SpanKind.tool, root, "turn:1/tool:c1", Map.of()).end();
            assertThatThrownBy(
                            () -> first.within("确认", SpanKind.confirmation, root, "turn:2/confirm", Map.of(), span -> {
                                throw new Suspend();
                            }))
                    .isInstanceOf(Suspend.class);
            first.suspendOpenSpans();
            assertThat(store.list(traceId).stream().map(SpanRecord::status))
                    .containsExactlyInAnyOrder(SpanStatus.ok, SpanStatus.suspended, SpanStatus.suspended);

            clock.now = Instant.parse("2026-10-15T08:05:00Z");
            var resumed = new Tracer(traceId, store, null, clock);
            var root2 = resumed.start("运行", SpanKind.run, null, "run", Map.of());
            assertThat(root2.spanId()).isEqualTo(root.spanId());
            // 重放时已完成的 span 再写一次也不会改变内容
            resumed.start("查询", SpanKind.tool, root2, "turn:1/tool:c1", Map.of())
                    .fail("不应覆盖");
            resumed.start("确认", SpanKind.confirmation, root2, "turn:2/confirm", Map.of())
                    .end();
            root2.end();

            var spans = store.list(traceId);
            assertThat(spans).hasSize(3);
            assertThat(spans).allMatch(s -> s.status() == SpanStatus.ok);
            var run = spans.stream()
                    .filter(s -> s.kind() == SpanKind.run)
                    .findFirst()
                    .orElseThrow();
            assertThat(run.startTime()).isEqualTo("2026-10-15T08:00:00.000Z");
            assertThat(run.endTime()).isEqualTo("2026-10-15T08:05:00.000Z");
        }
    }

    static SpanRecord modelSpan() {
        return new SpanRecord(
                "c".repeat(32),
                "1".repeat(16),
                null,
                "模型调用",
                SpanKind.model,
                "2026-10-15T08:00:00.000Z",
                "2026-10-15T08:00:01.000Z",
                SpanStatus.ok,
                null,
                Map.of("gen_ai.request.model", "scripted", "gen_ai.usage.input_tokens", 12, "eab.ratio", 0.5),
                List.of(
                        new PromptSegment("p1", "platform-rule", "platform/safety-rules@1.0.0", true, "平台规则原文"),
                        new PromptSegment("v1", "vendor-instructions", "vendor.pur/x@1.0.0", true, VENDOR_TEXT),
                        new PromptSegment("t1", "tenant-instructions", "tenant.t001.x/y@0.1.0", false, "租户追加")),
                "t001");
    }

    @Nested
    @DisplayName("按查看者裁剪")
    class Views {

        @Test
        @DisplayName("租户视角里找不到标品指令和平台规则原文，租户自己的片段照常显示")
        void tenantView() {
            String json = Json.MAPPER.writeValueAsString(
                    TraceView.view(List.of(modelSpan()), new Viewer.Tenant("t001"), Visibility::digestOf));
            assertThat(json)
                    .doesNotContain(VENDOR_TEXT)
                    .doesNotContain("平台规则原文")
                    .contains("租户追加")
                    .contains(Visibility.VENDOR_INSTRUCTION_LABEL)
                    .contains(Visibility.PLATFORM_RULE_LABEL);
        }

        @Test
        @DisplayName("平台视角看到原文；厂商看不到租户的运行；租户看不到别的租户")
        void access() {
            String json = Json.MAPPER.writeValueAsString(
                    TraceView.view(List.of(modelSpan()), new Viewer.Platform(), Visibility::digestOf));
            assertThat(json).contains(VENDOR_TEXT);
            assertThat(TraceView.canView(new Viewer.Vendor(), List.of(modelSpan())))
                    .isFalse();
            assertThat(TraceView.canView(new Viewer.Tenant("t002"), List.of(modelSpan())))
                    .isFalse();
            assertThat(TraceView.canView(new Viewer.Tenant("t001"), List.of(modelSpan())))
                    .isTrue();
            assertThat(TraceView.canView(new Viewer.Tenant("t001"), List.of())).isFalse();
        }
    }

    @Test
    @DisplayName("OTLP：输出 ExportTraceServiceRequest 结构，不带片段原文")
    void otlp() {
        var otlp = Otlp.export(List.of(modelSpan()), "eab-platform", Json.MAPPER::writeValueAsString);
        String json = Json.MAPPER.writeValueAsString(otlp);
        var span = Json.MAPPER
                .readTree(json)
                .path("resourceSpans")
                .get(0)
                .path("scopeSpans")
                .get(0)
                .path("spans")
                .get(0);
        assertThat(span.path("traceId").asString()).isEqualTo("c".repeat(32));
        assertThat(span.path("startTimeUnixNano").asString()).isEqualTo("1792051200000000000");
        assertThat(json).doesNotContain(VENDOR_TEXT).contains("p1,v1,t1").contains("\"intValue\":\"12\"");
        assertThat(json).contains("\"doubleValue\":0.5");
    }
}
