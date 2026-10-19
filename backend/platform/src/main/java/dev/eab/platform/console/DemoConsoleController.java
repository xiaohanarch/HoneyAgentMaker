package dev.eab.platform.console;

import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformProperties;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示控制台的演示状态接口（主提示词 17 节，Studio 右上角隐藏菜单）。演示模式且租户管理员可用，
 * 演示模式未开启时 404。触发业务事件与发布标品新版本沿用 /demo/v1/events 与 /hub/demo/upgrade。
 */
@RestController
@Tag(name = "console")
public class DemoConsoleController {

    private final DemoConsoleService console;
    private final PlatformServices services;
    private final boolean demoMode;
    private final RateLimiter limiter;

    public DemoConsoleController(
            PlatformServices services, PlatformProperties props, RuntimeService runtime, DemoMode demoMode) {
        this.console = new DemoConsoleService(services, props, runtime);
        this.services = services;
        this.demoMode = demoMode.enabled();
        this.limiter = new RateLimiter(10, 60_000, services.clock());
    }

    private Principal requireAdmin(HttpServletRequest request) {
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "演示模式未开启");
        Principal principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant)
                || !principal.user().roles().contains("tenant-admin")) {
            throw new ApiException(403, "FORBIDDEN", "演示控制台的演示状态操作只对租户管理员开放");
        }
        limiter.check("demo-console:" + principal.user().id());
        return principal;
    }

    @PostMapping("/demo/v1/reset")
    @Operation(summary = "演示控制台：重置演示数据（等价 pnpm seed:reset）")
    public ApiResponse<Map<String, Object>> reset(HttpServletRequest request) {
        requireAdmin(request);
        return ApiResponse.success(console.reset());
    }

    public record StoryBody(String story) {}

    @PostMapping("/demo/v1/story-start")
    @Operation(summary = "演示控制台：跳到某个故事的起点（A–E 纯重置，F 重置后发布完成态快照）")
    public ApiResponse<Map<String, Object>> storyStart(HttpServletRequest request, @RequestBody StoryBody body) {
        Principal principal = requireAdmin(request);
        return ApiResponse.success(console.storyStart(
                body == null ? null : body.story(), principal.user().tenantId()));
    }

    @PostMapping("/demo/v1/complete")
    @Operation(summary = "演示控制台：一键生成完成态（完成态快照 + 30 天模拟历史）")
    public ApiResponse<Map<String, Object>> complete(HttpServletRequest request) {
        Principal principal = requireAdmin(request);
        return ApiResponse.success(console.complete(principal.user().tenantId()));
    }
}
