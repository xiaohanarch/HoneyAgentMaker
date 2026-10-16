package dev.eab.platform.a2a;

import dev.eab.platform.assist.NotificationStore;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.spec.Json;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * A2A 推送回调（主提示词 8.4 节）：同步等待超时转为异步后，远程 Agent 把任务进展推送到这里。
 * 回调编号和令牌在发起调用时生成，令牌只存哈希；任务进入终态时把结果推送到发起用户的通知区。
 */
@Hidden
@RestController
public class A2aCallbackController {

    public static final String TOKEN_HEADER = "X-A2A-Notification-Token";

    static final Set<String> FINAL_STATES = Set.of("completed", "failed", "canceled", "rejected", "input-required");

    private final RemoteAgentGateway gateway;
    private final NotificationStore notifications;

    public A2aCallbackController(RemoteAgentGateway gateway, NotificationStore notifications) {
        this.gateway = gateway;
        this.notifications = notifications;
    }

    /** 推送内容的解析结果，state 为空表示这次推送没有状态变化（例如只是产出物） */
    record Update(String state, String text, boolean append) {}

    @PostMapping("/a2a/callbacks/{id}")
    public ApiResponse<Map<String, Object>> callback(
            @PathVariable String id,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestBody String body) {
        A2aTaskStore store = gateway.tasks();
        var task = store.get(id)
                .filter(t -> store.tokenMatches(t, token))
                .orElseThrow(() -> new ApiException(401, "INVALID_CALLBACK", "回调编号或令牌不正确"));
        Update update = parse(body);
        String text = task.text() == null ? "" : task.text();
        if (update.text() != null && !update.text().isBlank()) {
            text = update.append() && !"working".equals(task.state()) ? text + update.text() : update.text();
        }
        String state = update.state() == null ? task.state() : update.state();
        boolean finished = FINAL_STATES.contains(state) && !FINAL_STATES.contains(task.state());
        if (finished && text.isBlank()) {
            var refreshed = gateway.refresh(task);
            if (refreshed.isPresent()) text = refreshed.get().text();
        }
        store.update(id, state, text);
        if (finished) {
            String title = "input-required".equals(state) ? "外部 Agent 需要补充信息" : "外部 Agent 的异步任务已结束";
            notifications.add(task.tenantId(), task.userId(), title, text, "a2a:" + task.remote(), task.runId(), null);
        }
        return ApiResponse.success(Map.of("id", id, "state", state));
    }

    /** 解析 A2A 推送内容：StreamResponse（task、statusUpdate、artifactUpdate 之一）或者直接是 Task */
    static Update parse(String body) {
        JsonNode root;
        try {
            root = Json.MAPPER.readTree(body);
        } catch (RuntimeException e) {
            throw new ApiException(400, "INVALID_PAYLOAD", "推送内容不是合法的 JSON");
        }
        if (root.has("artifactUpdate")) {
            JsonNode artifact = root.get("artifactUpdate");
            return new Update(
                    null,
                    partsText(artifact.path("artifact").path("parts")),
                    artifact.path("append").asBoolean(false));
        }
        JsonNode node =
                root.has("statusUpdate") ? root.get("statusUpdate") : root.has("task") ? root.get("task") : root;
        JsonNode status = node.path("status");
        String state = status.path("state").isMissingNode()
                ? null
                : normalizeState(status.path("state").asText());
        List<String> texts = new ArrayList<>();
        for (JsonNode artifact : node.path("artifacts")) texts.add(partsText(artifact.path("parts")));
        if (texts.stream().allMatch(String::isBlank))
            texts.add(partsText(status.path("message").path("parts")));
        return new Update(
                state,
                String.join("\n", texts.stream().filter(t -> !t.isBlank()).toList()),
                false);
    }

    /** TASK_STATE_INPUT_REQUIRED、input-required、INPUT_REQUIRED 都归一成 input-required */
    static String normalizeState(String raw) {
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("task_state_")) s = s.substring("task_state_".length());
        s = s.replace('_', '-');
        return s.equals("cancelled") ? "canceled" : s;
    }

    private static String partsText(JsonNode parts) {
        List<String> texts = new ArrayList<>();
        for (JsonNode part : parts) {
            if (part.hasNonNull("text")) texts.add(part.get("text").asText());
        }
        return String.join("\n", texts);
    }
}
