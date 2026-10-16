package dev.eab.platform.studio;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.Principals.Principal;
import dev.eab.spec.Viewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 连接中心的接口（docs/studio-api.md）：租户开发者与管理员可以查看，只有租户管理员可以修改绑定 */
@RestController
@Tag(name = "studio")
public class ConnectionController {

    static final List<String> READ_ROLES = List.of("tenant-developer", "tenant-admin");
    static final String WRITE_ROLE = "tenant-admin";

    private final PlatformServices services;
    private final ConnectionCenter center;

    public ConnectionController(PlatformServices services) {
        this.services = services;
        this.center = new ConnectionCenter(services);
    }

    private Principal reader(HttpServletRequest request) {
        Principal principal = services.principals().require(request);
        boolean allowed = principal.viewer() instanceof Viewer.Tenant
                && principal.user().roles().stream().anyMatch(READ_ROLES::contains);
        if (!allowed) throw new ApiException(403, "FORBIDDEN", "连接中心只对租户开发者和租户管理员开放");
        return principal;
    }

    @GetMapping("/studio/connections")
    @Operation(summary = "本租户的连接别名与各环境的绑定")
    public ApiResponse<List<ConnectionCenter.ConnectionView>> list(HttpServletRequest request) {
        Principal principal = reader(request);
        boolean editable = principal.user().roles().contains(WRITE_ROLE);
        return ApiResponse.success(center.list(principal.user().tenantId()).stream()
                .map(v -> v.withEditable(editable))
                .toList());
    }

    public record BindingBody(String url, String authType, String credentialRef) {}

    @PutMapping("/studio/connections/{alias}/bindings/{environment}")
    @Operation(summary = "修改一个环境的绑定（租户管理员），写入 Connection 的新版本并发布")
    public ApiResponse<ConnectionCenter.ConnectionView> update(
            HttpServletRequest request,
            @PathVariable String alias,
            @PathVariable String environment,
            @RequestBody(required = false) BindingBody body) {
        Principal principal = reader(request);
        if (!principal.user().roles().contains(WRITE_ROLE)) {
            throw new ApiException(403, "FORBIDDEN", "只有租户管理员可以修改连接绑定");
        }
        if (body == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少 url 和 authType");
        try {
            return ApiResponse.success(center.updateBinding(
                            principal.user().tenantId(),
                            principal.user().id(),
                            alias,
                            environment,
                            body.url(),
                            body.authType(),
                            body.credentialRef())
                    .withEditable(true));
        } catch (ConnectionCenter.ConnectionException e) {
            throw new ApiException(e.status(), e.code(), e.getMessage());
        }
    }
}
