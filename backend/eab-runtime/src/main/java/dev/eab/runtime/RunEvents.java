package dev.eab.runtime;

import dev.eab.tracing.Tracer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** 运行事件与挂起信号 */
public final class RunEvents {

    private RunEvents() {}

    /**
     * 运行事件，逐个推给调用方（HTTP 接口用 SSE，命令行直接打印）。
     *
     * <ul>
     *   <li>step：思考步骤摘要，不是模型的原始推理
     *   <li>tool_call、tool_result：工具调用卡片
     *   <li>confirmation_required：确认卡片，附将要写入的内容预览
     *   <li>text_delta：最终回复的流式片段
     *   <li>final：最终结果（回复、结构化结果、追踪编号）
     *   <li>error：运行失败
     * </ul>
     *
     * @param agent 产生事件的 Agent（子 Agent 的事件也挂在同一次运行下）
     */
    public record RunEvent(String type, String agent, Map<String, Object> data) {

        public static RunEvent of(String type, String agent, Object... keyValues) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                if (keyValues[i + 1] != null) data.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
            return new RunEvent(type, agent, data);
        }
    }

    /** 事件出口 */
    public interface Sink extends Consumer<RunEvent> {}

    /** 运行等待人工确认（或业务事件）时抛出；不算错误，span 以 suspended 落库 */
    public static final class Suspended extends RuntimeException implements Tracer.Suspension {
        private final Journal.Pending pending;

        public Suspended(Journal.Pending pending) {
            super("等待确认：" + pending.title());
            this.pending = pending;
        }

        public Journal.Pending pending() {
            return pending;
        }
    }
}
