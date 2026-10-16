package dev.eab.platform.a2a;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Studio“接入外部 Agent”页面的接口，只对租户开发者和租户管理员开放 */
@RestController
public class ExternalAgentController {

    static final List<String> ROLES = List.of("tenant-developer", "tenant-admin");
    static final int MAX_URL = 500;
    static final int MAX_MESSAGE = 1000;

    private final PlatformServices services;
    private final ExternalAgentService external;
    private final InboundAgents inbound;
    private final RateLimiter limiter;

    public ExternalAgentController(PlatformServices services, ExternalAgentService external, InboundAgents inbound) {
        this.services = services;
        this.external = external;
        this.inbound = inbound;
        this.limiter = new RateLimiter(30, 60_000, services.clock());
    }

    private DemoUser developer(HttpServletRequest request) {
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)
                || principal.user().roles().stream().noneMatch(ROLES::contains)) {
            throw new ApiException(403, "FORBIDDEN", "接入外部 Agent 只对租户开发者和租户管理员开放");
        }
        limiter.check("external:" + principal.user().id());
        return principal.user();
    }

    static String text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：" + field + " 不能为空，最长 " + max + " 字");
        }
        return value.strip();
    }

    public record CardBody(String cardUrl, String message) {}

    @PostMapping("/studio/external/inspect")
    @Operation(summary = "拉取并解读外部 Agent 的 Agent Card，给出接入配置的建议")
    public ApiResponse<Map<String, Object>> inspect(HttpServletRequest request, @RequestBody CardBody body) {
        DemoUser user = developer(request);
        return ApiResponse.success(external.inspect(user, text(body == null ? null : body.cardUrl(), "cardUrl", MAX_URL)));
    }

    @PostMapping("/studio/external/test")
    @Operation(summary = "连通性测试：发一条消息，返回回复和 A2A 请求、响应原文")
    public ApiResponse<Map<String, Object>> test(HttpServletRequest request, @RequestBody CardBody body) {
        DemoUser user = developer(request);
        if (body == null) throw new ApiException(400, "BAD_REQUEST", "请求体不能为空");
        return ApiResponse.success(external.test(
                user, text(body.cardUrl(), "cardUrl", MAX_URL), text(body.message(), "message", MAX_MESSAGE)));
    }

    @PostMapping("/studio/external/publish")
    @Operation(summary = "发布外部 Agent 的配置为 RemoteAgent 资产，发布后进入能力目录")
    public ApiResponse<Map<String, Object>> publish(
            HttpServletRequest request, @RequestBody ExternalAgentService.PublishRequest body) {
        DemoUser user = developer(request);
        if (body == null) throw new ApiException(400, "BAD_REQUEST", "请求体不能为空");
        text(body.cardUrl(), "cardUrl", MAX_URL);
        text(body.displayName(), "displayName", 80);
        return ApiResponse.success(external.publish(user, body));
    }

    @GetMapping("/studio/external")
    @Operation(summary = "本租户已接入的外部 Agent，以及平台对外暴露的 A2A Agent")
    public ApiResponse<Map<String, Object>> list(HttpServletRequest request) {
        DemoUser user = developer(request);
        var exposed = inbound.list(user.tenantId()).stream()
                .map(e -> Map.<String, Object>of(
                        "ref", e.ref(),
                        "name", e.name(),
                        "kind", e.kind(),
                        "cardUrl", inbound.endpoint(e) + "/.well-known/agent-card.json"))
                .toList();
        var remotes = external.remotes(user.tenantId());
        return ApiResponse.success(Map.of("remotes", remotes, "exposed", exposed));
    }
}
