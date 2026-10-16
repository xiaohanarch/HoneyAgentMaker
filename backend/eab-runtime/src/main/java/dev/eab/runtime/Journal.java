package dev.eab.runtime;

import dev.eab.runtime.model.Masker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 运行日志：按结构化路径（例如 turn:2/tool:call_1）记录模型调用、工具调用和确认结果（ADR 0008）。
 *
 * <p>运行等待人工确认时挂起，保存的状态就是这份日志。用户确认或拒绝后，运行从头重新执行：日志里已有的步骤直接取记录的结果，
 * 不再调用模型、不再重复写入 ERP，运行一直重放到挂起的位置，再按用户的决定继续。这样不需要把 Spring AI 的消息、
 * 调用栈等对象序列化，子 Agent、技能、工作流的挂起也能用同一种方式恢复。
 */
public final class Journal {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * 等待确认的位置。
     *
     * @param kind confirmation（人工确认）或 event（等待业务事件）
     * @param preview 给用户看的内容：将要写入的内容、确认消息
     */
    public record Pending(String key, String kind, String title, Map<String, Object> preview) {}

    /** 可序列化的状态 */
    public record State(
            Map<String, JsonNode> entries,
            Map<String, Boolean> decisions,
            Pending pending,
            List<Masker.Entry> maskerEntries) {}

    private final Map<String, JsonNode> entries;
    private final Map<String, Boolean> decisions;
    private final Masker masker;
    private Pending pending;

    public Journal() {
        this(new State(Map.of(), Map.of(), null, List.of()));
    }

    public Journal(State state) {
        this.entries = new LinkedHashMap<>(state.entries() == null ? Map.of() : state.entries());
        this.decisions = new LinkedHashMap<>(state.decisions() == null ? Map.of() : state.decisions());
        this.masker = Masker.from(state.maskerEntries() == null ? List.of() : state.maskerEntries());
        this.pending = state.pending();
    }

    public synchronized State state() {
        return new State(Map.copyOf(entries), Map.copyOf(decisions), pending, new ArrayList<>(masker.entries()));
    }

    public Masker masker() {
        return masker;
    }

    public synchronized boolean has(String key) {
        return entries.containsKey(key);
    }

    /** 已记录时返回记录的结果（重放），否则执行并记录 */
    public <T> T record(String key, Class<T> type, Callable<T> work) throws Exception {
        synchronized (this) {
            JsonNode recorded = entries.get(key);
            if (recorded != null) return JSON.treeToValue(recorded, type);
        }
        T result = work.call();
        synchronized (this) {
            entries.put(key, JSON.valueToTree(result));
        }
        return result;
    }

    /** 用户对某个确认点的决定；还没有决定时为 null */
    public synchronized Boolean decision(String key) {
        return decisions.get(key);
    }

    public synchronized void suspend(Pending value) {
        this.pending = value;
    }

    public synchronized Pending pending() {
        return pending;
    }

    /** 用户确认或拒绝：记录决定，清除挂起状态，之后重新执行运行 */
    public synchronized void decide(boolean approved) {
        if (pending == null) throw new IllegalStateException("运行没有在等待确认");
        decisions.put(pending.key(), approved);
        pending = null;
    }

    public static String toJson(State state) {
        return JSON.writeValueAsString(state);
    }

    public static State fromJson(String json) {
        return JSON.readValue(json, State.class);
    }
}
