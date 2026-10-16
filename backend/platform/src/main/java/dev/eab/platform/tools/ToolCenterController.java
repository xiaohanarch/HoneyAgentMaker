package dev.eab.platform.tools;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.extend.ExtendController;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 工具中心的接口（docs/studio-api.md 的 P6 一节） */
@RestController
@Tag(name = "studio-tools")
public class ToolCenterController {

    static final String NAME = "^[a-z][a-z0-9-]{2,40}$";
    static final String ALIAS = "^[A-Z][A-Z0-9_]{1,40}$";

    private final PlatformServices services;
    private final ToolCenter tools;
    private final RateLimiter limiter;

    public ToolCenterController(PlatformServices services, ToolCenter tools) {
        this.services = services;
        this.tools = tools;
        this.limiter = new RateLimiter(30, 60_000, services.clock());
    }

    private DemoUser developer(HttpServletRequest request) {
        return ExtendController.developer(services, request);
    }

    private static void check(String value, String pattern, String message) {
        if (value == null || !value.matches(pattern)) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：" + message);
    }

    @GetMapping("/studio/tools")
    @Operation(summary = "本租户可见的工具来源与技能，带写操作级别、MCP 审批状态和被引用关系")
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request) {
        return ApiResponse.success(tools.list(developer(request).tenantId()));
    }

    @GetMapping("/studio/tools/bo-operations")
    @Operation(summary = "从 DemoERP 的 OpenAPI 读取可以导入的 BO 操作")
    public ApiResponse<List<Map<String, Object>>> boOperations(HttpServletRequest request) {
        DemoUser user = developer(request);
        return ApiResponse.success(ExtendController.call(() -> tools.boOperations(user.tenantId())));
    }

    public record BoImportBody(String name, String displayName, List<String> operations) {}

    @PostMapping("/studio/tools/bo-import")
    @Operation(summary = "勾选 BO 操作生成租户的工具来源（描述、参数、写操作级别取自 OpenAPI）")
    public ApiResponse<Map<String, Object>> importBo(HttpServletRequest request, @RequestBody BoImportBody body) {
        DemoUser user = developer(request);
        if (body == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法");
        check(body.name(), NAME, "name 用小写字母、数字和连字符，3 到 41 个字符");
        limiter.check("tools:" + user.id());
        return ApiResponse.success(
                ExtendController.call(() -> tools.importBo(user, body.name(), body.displayName(), body.operations())));
    }

    @GetMapping("/studio/tools/mcp-tools")
    @Operation(summary = "按 MCP 类型的连接别名拉取外部 MCP Server 的工具清单（dev 环境的绑定）")
    public ApiResponse<List<Map<String, Object>>> mcpTools(
            HttpServletRequest request, @RequestParam String connection) {
        DemoUser user = developer(request);
        check(connection, ALIAS, "connection 写连接别名，例如 LOGISTICS_MCP");
        limiter.check("tools:" + user.id());
        return ApiResponse.success(ExtendController.call(() -> tools.mcpTools(user.tenantId(), connection)));
    }

    public record McpRegisterBody(
            String name, String displayName, String connection, List<ToolCenter.McpToolChoice> tools) {}

    @PostMapping("/studio/tools/mcp-servers")
    @Operation(summary = "登记 MCP Server：生成 mcp 工具来源并提交审批，审批通过前不能用在 prod")
    public ApiResponse<Map<String, Object>> registerMcp(HttpServletRequest request, @RequestBody McpRegisterBody body) {
        DemoUser user = developer(request);
        if (body == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法");
        check(body.name(), NAME, "name 用小写字母、数字和连字符，3 到 41 个字符");
        check(body.connection(), ALIAS, "connection 写连接别名，例如 LOGISTICS_MCP");
        limiter.check("tools:" + user.id());
        return ApiResponse.success(ExtendController.call(
                () -> tools.registerMcp(user, body.name(), body.displayName(), body.connection(), body.tools())));
    }

    @GetMapping("/studio/mcp-approvals")
    @Operation(summary = "本租户的 MCP Server 审批记录")
    public ApiResponse<List<McpApprovalStore.Approval>> approvals(HttpServletRequest request) {
        return ApiResponse.success(tools.approvals().list(developer(request).tenantId()));
    }

    public record DecisionBody(Boolean approved, String note) {}

    @PostMapping("/studio/mcp-approvals/{id}/decision")
    @Operation(summary = "审批 MCP Server（租户管理员）")
    public ApiResponse<McpApprovalStore.Approval> decide(
            HttpServletRequest request, @PathVariable long id, @RequestBody DecisionBody body) {
        DemoUser user = developer(request);
        if (!user.roles().contains("tenant-admin")) throw new ApiException(403, "FORBIDDEN", "只有租户管理员可以审批 MCP Server");
        if (body == null || body.approved() == null)
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：approved 必须是 true 或 false");
        if (body.note() != null && body.note().length() > 500)
            throw new ApiException(400, "BAD_REQUEST", "审批意见最长 500 字");
        return ApiResponse.success(ExtendController.call(() -> tools.decide(user, id, body.approved(), body.note())));
    }
}
