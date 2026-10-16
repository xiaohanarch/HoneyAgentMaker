package dev.eab.a2a;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;

/**
 * 任务归属：任务记在创建它的调用方名下，只有同一调用方能查询、续接、订阅、取消。未认证的调用方不能创建任务。
 * 调用方的标识取自调用上下文里的用户名（平台填入“租户/用户”）。
 */
public final class OwnerTaskAuthorization implements TaskAuthorizationProvider {

    private final Map<String, String> owners = new ConcurrentHashMap<>();

    static String caller(ServerCallContext context) {
        if (context == null || context.getUser() == null || !context.getUser().isAuthenticated()) return null;
        return context.getUser().getUsername();
    }

    private boolean owns(ServerCallContext context, String taskId) {
        String caller = caller(context);
        return caller != null && caller.equals(owners.get(taskId));
    }

    @Override
    public boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) {
        // 列出任务时 taskId 为空：只要已认证即可，列表结果由任务存储按调用方过滤
        if (taskId == null || taskId.isEmpty()) return caller(context) != null;
        return owns(context, taskId);
    }

    @Override
    public boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) {
        return owns(context, taskId);
    }

    @Override
    public boolean checkCreate(ServerCallContext context, TaskOperation operation) {
        return caller(context) != null;
    }

    @Override
    public boolean isTaskRecorded(String taskId) {
        return owners.containsKey(taskId);
    }

    @Override
    public void recordOwnership(ServerCallContext context, String taskId, TaskOperation operation) {
        String caller = caller(context);
        if (caller != null) owners.putIfAbsent(taskId, caller);
    }
}
