package dev.eab.platform.audit;

import dev.eab.platform.audit.AuditStore.AuditEvent;
import dev.eab.platform.audit.AuditStore.AuditFilter;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计查询（主提示词 7.5 节）：按用户、Agent、工具、时间、结果查询，
 * 用 target 可以反查“PO-2026-0117 的交期是谁通过哪个 Agent 改的”。
 * 平台身份能看全部；租户管理员看本租户；普通租户用户只能看自己的记录；厂商身份看不到任何租户的审计。
 */
@RestController
@Tag(name = "audit")
public class AuditController {

    private static final List<String> DECISIONS = List.of("allow", "deny", "confirm", "approve");

    private final AuditStore audit;
    private final Principals principals;

    public AuditController(AuditStore audit, Principals principals) {
        this.audit = audit;
        this.principals = principals;
    }

    @GetMapping("/audit/events")
    @Operation(summary = "查询审计事件")
    public ApiResponse<List<AuditEvent>> list(
            HttpServletRequest request,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String checkpoint,
            @RequestParam(required = false) String target,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer limit) {
        var principal = principals.require(request);
        Viewer viewer = principal.viewer();
        if (viewer instanceof Viewer.Vendor) throw new ApiException(403, "FORBIDDEN", "厂商身份不能查看租户审计");
        if (decision != null && !DECISIONS.contains(decision)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：decision 只能是 allow、deny、confirm、approve");
        }
        if (limit != null && (limit < 1 || limit > 500)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：limit 应在 1 到 500 之间");
        }
        String tenantId = null;
        String scopedUser = userId;
        if (viewer instanceof Viewer.Tenant t) {
            tenantId = t.tenantId();
            // 普通租户用户只能查自己的记录
            if (!principal.user().roles().contains("tenant-admin"))
                scopedUser = principal.user().id();
        }
        return ApiResponse.success(audit.list(new AuditFilter(
                action, decision, tenantId, scopedUser, agent, tool, checkpoint, target, from, to, limit)));
    }
}
