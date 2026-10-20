package dev.eab.platform.identity;

import dev.eab.common.IsoTime;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.PublicUser;
import dev.eab.platform.DemoMode;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.http.RateLimiter;
import dev.eab.platform.identity.TokenService.TokenException;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Layers;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模拟身份服务的接口（主提示词 7.1、7.2 节，ADR 0005）。
 * 选人登录、HTTP 令牌交换和代签 Agent 令牌只在演示模式开放；令牌交换失败绝不退回任何技术账号。
 */
@RestController
@Tag(name = "identity")
public class IdentityController {

    public static final String INTERNAL_HEADER = "x-eab-internal-token";

    private static final Pattern AGENT_REF = Pattern.compile("^[a-z0-9.-]+/[a-z0-9-]+$");
    private static final Pattern PAGE_ID = Pattern.compile("^[A-Z0-9-]{1,32}$");

    private final PlatformServices services;
    private final boolean demoMode;
    private final RateLimiter limiter;
    private final DemoPasscodes passcodes;

    public IdentityController(PlatformServices services, DemoMode demoMode, Environment env) {
        this.services = services;
        this.demoMode = demoMode.enabled();
        // 登录限流默认每 IP 每分钟 30 次；演示环境共用回环地址，由 EAB_LOGIN_RATE_PER_MINUTE 调高
        this.limiter = new RateLimiter(
                env.getProperty("eab.login-rate-per-minute", Integer.class, 30), 60_000, services.clock());
        // 公网部署时按身份的登录口令（EAB_DEMO_PASSCODES）；未设置时保持选人即登录
        this.passcodes = DemoPasscodes.parse(env.getProperty("eab.demo-passcodes", ""));
    }

    /** 平台与 DemoERP 之间的内部调用校验，常量时间比较 */
    public static boolean isInternalCall(String header, String secret) {
        if (header == null) return false;
        return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    private void requireDemoMode() {
        if (!demoMode) throw new ApiException(404, "NOT_FOUND", "接口不存在");
    }

    /** 公钥对所有下游开放，任何模式下都提供 */
    @GetMapping("/identity/v1/jwks.json")
    @Operation(summary = "令牌校验公钥（JWKS）")
    public Map<String, Object> jwks() {
        return services.tokens().jwks();
    }

    @GetMapping("/identity/v1/users")
    @Operation(summary = "演示用户列表（选人即登录）")
    public ApiResponse<List<PublicUser>> users() {
        requireDemoMode();
        return ApiResponse.success(
                DemoDirectory.USERS.stream().map(DemoDirectory::toPublicUser).toList());
    }

    public record LoginBody(String userId, String passcode) {}

    public record LoginResult(String token, PublicUser user) {}

    public record LoginConfig(boolean passcodeRequired) {}

    @GetMapping("/identity/v1/login-config")
    @Operation(summary = "登录配置：是否启用了按身份口令（公网部署时前端据此出口令输入框）")
    public ApiResponse<LoginConfig> loginConfig() {
        requireDemoMode();
        return ApiResponse.success(new LoginConfig(passcodes.required()));
    }

    @PostMapping("/identity/v1/login")
    @Operation(summary = "选人登录，签发用户令牌（仅演示模式开放；设置了口令表时需要 passcode）")
    public ApiResponse<LoginResult> login(HttpServletRequest request, @RequestBody LoginBody body) {
        requireDemoMode();
        limiter.check(request.getRemoteAddr());
        // 公网端点不接受任意大的请求体：userId 与 passcode 都限长
        if (body == null
                || body.userId() == null
                || body.userId().length() > 64
                || (body.passcode() != null && body.passcode().length() > 128)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少 userId 或字段过长");
        }
        checkPasscode(body);
        try {
            var login = services.identity().login(body.userId());
            return ApiResponse.success(new LoginResult(login.token(), DemoDirectory.toPublicUser(login.user())));
        } catch (TokenException e) {
            throw new ApiException(404, "UNKNOWN_USER", e.getMessage());
        }
    }

    /** 公网口令门禁：无条目的身份拒绝公网登录，未带或不对的口令都拒绝；比较用常量时间 */
    private void checkPasscode(LoginBody body) {
        if (!passcodes.required()) return;
        String expected = passcodes.lookup(body.userId());
        if (expected == null) {
            throw new ApiException(401, "LOGIN_DISABLED", "该身份未配置公网登录口令");
        }
        if (body.passcode() == null) {
            throw new ApiException(401, "PASSCODE_REQUIRED", "该身份需要口令登录");
        }
        if (!MessageDigest.isEqual(
                body.passcode().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(401, "INVALID_PASSCODE", "口令不正确");
        }
    }

    /**
     * 令牌端点：客户端凭证；演示模式下另开放 RFC 8693 令牌交换（运行时在进程内交换）。
     * 按 RFC 6749 惯例同时接受表单编码和 JSON，错误也用 RFC 6749 的格式。
     */
    @PostMapping(value = "/identity/v1/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "令牌端点（表单编码）")
    public ResponseEntity<Map<String, Object>> tokenForm(
            HttpServletRequest request, @RequestParam MultiValueMap<String, String> form) {
        return token(request, form.toSingleValueMap());
    }

    @PostMapping(value = "/identity/v1/token", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "令牌端点：客户端凭证；演示模式下另开放 RFC 8693 令牌交换")
    public ResponseEntity<Map<String, Object>> tokenJson(
            HttpServletRequest request, @RequestBody Map<String, String> body) {
        return token(request, body);
    }

    private ResponseEntity<Map<String, Object>> token(HttpServletRequest request, Map<String, String> body) {
        limiter.check(request.getRemoteAddr());
        String grant = body.get("grant_type");
        try {
            if ("client_credentials".equals(grant)) {
                String token = services.identity().clientCredentials(body.get("client_id"), body.get("client_secret"));
                return ResponseEntity.ok(Map.of("access_token", token, "token_type", "Bearer", "expires_in", 3600));
            }
            if (!"urn:ietf:params:oauth:grant-type:token-exchange".equals(grant)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "unsupported_grant_type", "error_description", "不支持的 grant_type"));
            }
            // 生产形态下令牌交换由平台运行时在进程内完成，HTTP 端点只在演示模式开放，用于手工验证
            if (!demoMode) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "unsupported_grant_type", "error_description", "令牌交换只在平台内部进行"));
            }
            // 受限回调交换（ADR 0014）：外部 Agent 用平台出站调用的服务令牌换只读委托令牌，受众固定 eab-platform
            if (IdentityService.TokenType.SERVICE.equals(body.get("subject_token_type"))) {
                if (!TokenService.Audience.PLATFORM.equals(body.get("audience"))) {
                    return ResponseEntity.badRequest()
                            .body(Map.of(
                                    "error",
                                    "invalid_request",
                                    "error_description",
                                    "服务令牌只能换取受众为 eab-platform 的受限回调令牌"));
                }
                if (body.get("actor_token") != null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "invalid_request", "error_description", "服务令牌交换不需要 actor_token"));
                }
                if (body.get("subject_token") == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "invalid_request", "error_description", "缺少 subject_token"));
                }
                String restrictedScope = body.get("scope");
                var restricted = services.identity()
                        .exchangeRestricted(
                                body.get("subject_token"),
                                restrictedScope == null
                                        ? null
                                        : Arrays.stream(restrictedScope.split(" "))
                                                .filter(s -> !s.isEmpty())
                                                .toList());
                return ResponseEntity.ok(Map.of(
                        "access_token",
                        restricted.accessToken(),
                        "issued_token_type",
                        IdentityService.TokenType.ACCESS_TOKEN,
                        "token_type",
                        "Bearer",
                        "expires_in",
                        restricted.expiresIn(),
                        "scope",
                        String.join(" ", restricted.scope())));
            }
            for (String field : List.of("subject_token", "subject_token_type", "actor_token", "audience")) {
                if (body.get(field) == null) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("error", "invalid_request", "error_description", "缺少 " + field));
                }
            }
            String scope = body.get("scope");
            var result = services.identity()
                    .exchange(new IdentityService.ExchangeRequest(
                            body.get("subject_token"),
                            body.get("subject_token_type"),
                            body.get("actor_token"),
                            body.get("audience"),
                            scope == null
                                    ? null
                                    : Arrays.stream(scope.split(" "))
                                            .filter(s -> !s.isEmpty())
                                            .toList()));
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("access_token", result.accessToken());
            response.put("issued_token_type", IdentityService.TokenType.ACCESS_TOKEN);
            response.put("token_type", "Bearer");
            response.put("expires_in", result.expiresIn());
            response.put("scope", String.join(" ", result.scope()));
            return ResponseEntity.ok(response);
        } catch (TokenException e) {
            // RFC 6749 的错误格式；令牌交换失败绝不退回任何技术账号
            return ResponseEntity.badRequest()
                    .body(Map.of(
                            "error", e.code().equals("INVALID_CLIENT") ? "invalid_client" : "invalid_grant",
                            "error_code", e.code(),
                            "error_description", e.getMessage()));
        }
    }

    // ── 委托管理（租户管理员） ───────────────────────────────────────────

    @GetMapping("/identity/v1/delegations")
    @Operation(summary = "本租户的委托清单")
    public ApiResponse<List<DelegationStore.Delegation>> delegations(HttpServletRequest request) {
        var principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant t)) return ApiResponse.success(List.of());
        return ApiResponse.success(services.delegations().listByTenant(t.tenantId()));
    }

    public record DelegationBody(
            String delegatorUserId, String agent, List<String> scope, Integer validDays, String note) {}

    @PostMapping("/identity/v1/delegations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "登记委托（租户管理员）")
    public ApiResponse<DelegationStore.Delegation> createDelegation(
            HttpServletRequest request, @RequestBody DelegationBody body) {
        var principal = services.principals().require(request);
        validate(body);
        var user = principal.user();
        var delegator = DemoDirectory.findUser(body.delegatorUserId()).orElse(null);
        if (!user.roles().contains("tenant-admin")
                || delegator == null
                || !delegator.tenantId().equals(user.tenantId())) {
            throw new ApiException(403, "FORBIDDEN", "只有本租户的租户管理员可以为本租户用户登记委托");
        }
        List<String> unknown = body.scope().stream()
                .filter(op -> !DemoDirectory.isBoOperation(op))
                .toList();
        if (!unknown.isEmpty())
            throw new ApiException(422, "UNKNOWN_OPERATION", "未知的 BO 操作：" + String.join("、", unknown));
        // 受托运行没有在线的人确认，只能委托只读操作（写级别以 BO 工具来源的声明为准）
        List<String> writes = body.scope().stream()
                .filter(op -> !boWriteLevel(op).equals("L0"))
                .toList();
        if (!writes.isEmpty()) {
            throw new ApiException(422, "WRITE_NOT_DELEGABLE", "受托自主运行只能委托只读操作，不能委托：" + String.join("、", writes));
        }
        boolean agentExists = services.registry()
                .resolverFor(principal.viewer())
                .resolveOfKind(body.agent(), AgentAsset.class, dev.eab.spec.AssetResolver.PUBLISHED)
                .isPresent();
        if (!agentExists) throw new ApiException(422, "UNKNOWN_AGENT", "Agent " + body.agent() + " 不存在或本租户不可用");
        var now = services.clock().instant();
        int days = body.validDays() == null ? 30 : body.validDays();
        var delegation = services.delegations()
                .create(new DelegationStore.NewDelegation(
                        user.tenantId(),
                        delegator.id(),
                        body.agent(),
                        body.scope(),
                        IsoTime.format(now),
                        IsoTime.format(now.plus(Duration.ofDays(days))),
                        body.note(),
                        user.id()));
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(),
                                "identity.delegation.create",
                                delegation.id(),
                                "allow",
                                delegator.name() + " 委托 " + delegation.agent())
                        .tenantId(user.tenantId())
                        .userId(delegator.id())
                        .agent(delegation.agent()));
        return ApiResponse.success(delegation);
    }

    private static void validate(DelegationBody body) {
        boolean ok = body != null
                && body.delegatorUserId() != null
                && body.agent() != null
                && AGENT_REF.matcher(body.agent()).matches()
                && body.scope() != null
                && !body.scope().isEmpty()
                && body.scope().size() <= 16
                && body.scope().stream().allMatch(s -> s != null && s.length() <= 64)
                && (body.validDays() == null || (body.validDays() >= 1 && body.validDays() <= 365))
                && (body.note() == null || body.note().length() <= 500);
        if (!ok) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法");
    }

    @DeleteMapping("/identity/v1/delegations/{id}")
    @Operation(summary = "撤销委托，即时生效（租户管理员）")
    public ApiResponse<DelegationStore.Delegation> revoke(HttpServletRequest request, @PathVariable String id) {
        var principal = services.principals().require(request);
        var existing = services.delegations()
                .get(id)
                .filter(d -> d.tenantId().equals(principal.user().tenantId()))
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "委托 " + id + " 不存在"));
        if (!principal.user().roles().contains("tenant-admin")) {
            throw new ApiException(403, "FORBIDDEN", "只有租户管理员可以撤销委托");
        }
        var revoked = services.delegations()
                .revoke(existing.id(), principal.user().id())
                .orElseThrow();
        services.audit()
                .append(NewAuditEvent.of(
                                principal.user().id(),
                                "identity.delegation.revoke",
                                existing.id(),
                                "allow",
                                "撤销 " + existing.agent() + " 的委托")
                        .tenantId(existing.tenantId())
                        .userId(existing.delegatorUserId())
                        .agent(existing.agent()));
        return ApiResponse.success(revoked);
    }

    @GetMapping("/identity/v1/delegations/{id}/status")
    @Operation(summary = "核实委托是否有效（供 DemoERP 调用，需要内部调用密钥）")
    public ApiResponse<Map<String, Object>> status(HttpServletRequest request, @PathVariable String id) {
        if (!isInternalCall(request.getHeader(INTERNAL_HEADER), services.internalSecret())) {
            throw new ApiException(401, "INTERNAL_ONLY", "只接受内部调用");
        }
        var status = services.delegations().status(id);
        return ApiResponse.success(
                status.active()
                        ? Map.of(
                                "active",
                                true,
                                "tenantId",
                                status.delegation().tenantId(),
                                "agent",
                                status.delegation().agent())
                        : Map.of("active", false, "reason", status.reason()));
    }

    // ── DemoERP 只读页面的深链接：带短期页面令牌，页面按该用户的数据权限展示 ──

    public record PageLinkBody(String kind, String id) {}

    @PostMapping("/identity/v1/page-links")
    @Operation(summary = "生成 DemoERP 只读页面的深链接（10 分钟有效，按当前用户的数据权限展示）")
    public ApiResponse<Map<String, String>> pageLink(HttpServletRequest request, @RequestBody PageLinkBody body) {
        var principal = services.principals().require(request);
        if (body == null
                || !List.of("supplier", "purchase-order").contains(body.kind())
                || body.id() == null
                || !PAGE_ID.matcher(body.id()).matches()) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法");
        }
        String token = services.tokens()
                .issuePage(principal.user().id(), principal.user().tenantId());
        String segment = body.kind().equals("supplier") ? "suppliers" : "purchase-orders";
        return ApiResponse.success(Map.of(
                "path",
                "/erp/pages/" + segment + "/" + URLEncoder.encode(body.id(), StandardCharsets.UTF_8) + "?t=" + token));
    }

    // ── 演示模式：平台运维以运行时身份为 Agent 签发令牌，用于手工验证令牌交换 ──

    public record AgentTokenBody(String namespace, String name, String tenantId) {}

    public record AgentToken(String token, List<String> operations) {}

    @PostMapping("/identity/v1/demo/agent-token")
    @Operation(summary = "演示模式：为某租户组装 Agent 并签发 Agent 令牌（平台运维）")
    public ApiResponse<AgentToken> demoAgentToken(HttpServletRequest request, @RequestBody AgentTokenBody body) {
        requireDemoMode();
        var principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Platform)) {
            throw new ApiException(403, "FORBIDDEN", "只有平台运维可以代运行时签发 Agent 令牌");
        }
        if (body == null
                || body.namespace() == null
                || !Layers.isValidNamespace(body.namespace())
                || body.name() == null
                || !body.name().matches("^[a-z0-9][a-z0-9-]*$")
                || body.tenantId() == null) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法");
        }
        if (DemoDirectory.findTenant(body.tenantId()).isEmpty()) {
            throw new ApiException(422, "UNKNOWN_TENANT", "租户 " + body.tenantId() + " 不存在");
        }
        Viewer scope = Viewer.tenant(body.tenantId());
        String agent = body.namespace() + "/" + body.name();
        var outcome = EffectiveAssembly.assemble(
                services.registry(), scope, EffectiveAssembly.Request.of(agent, body.tenantId()));
        if (!(outcome instanceof EffectiveAssembly.Outcome.Ok success)) {
            throw new ApiException(422, new ApiError("ASSEMBLY_FAILED", "组装失败", outcome.issues(), null));
        }
        var operations = AgentScope.declaredBoOperations(
                success.definition(), services.registry().resolverFor(scope));
        String version = success.definition().provenance().agent().split("@")[1];
        String token = services.identity().issueAgentToken(agent, version, body.tenantId(), operations);
        return ApiResponse.success(new AgentToken(token, operations));
    }

    /** BO 操作的写级别，以已发布的 BO 工具来源为准；找不到时按最严格的 L3 处理 */
    private String boWriteLevel(String operation) {
        return services.registry()
                .resolverFor(Viewer.PLATFORM)
                .resolveOfKind(
                        AssetRef.key("vendor.erp.tools", "demo-erp-bo"),
                        ToolSourceAsset.class,
                        dev.eab.spec.AssetResolver.PUBLISHED)
                .flatMap(source -> source.spec().tools().stream()
                        .filter(t -> t.name().equals(operation))
                        .findFirst())
                .map(t -> t.writeLevel() == null ? "L3" : t.writeLevel())
                .orElse("L3");
    }
}
