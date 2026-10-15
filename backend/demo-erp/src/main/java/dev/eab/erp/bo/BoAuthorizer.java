package dev.eab.erp.bo;

import com.nimbusds.jwt.JWTParser;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.directory.DemoDirectory.Organization;
import dev.eab.directory.DemoDirectory.Tenant;
import dev.eab.erp.bo.BoTypes.BoContext;
import dev.eab.erp.bo.BoTypes.BoError;
import dev.eab.erp.bo.BoTypes.CallerIdentity;
import dev.eab.erp.bo.BoTypes.Dimension;
import dev.eab.erp.bo.BoTypes.Scope;
import dev.eab.erp.link.PlatformLink;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 鉴权点 4（主提示词 7.3 节）。依次检查：委托令牌有效 → 带 Agent 身份 → 租户一致 →
 * 用户有业务权限 → Agent 声明过这个操作 → 受托运行的委托仍有效 → 计算数据权限。
 * 任何一步不过都抛 BoError，不存在降级路径。
 */
public final class BoAuthorizer {

    public static final String AUDIENCE = "demo-erp-bo";

    private final PlatformLink link;

    public BoAuthorizer(PlatformLink link) {
        this.link = link;
    }

    public BoContext authorize(String authorization, String operation) {
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (token == null) {
            throw new BoError(401, "AUTH_REQUIRED", "缺少委托令牌", "由平台以“用户 + Agent”身份换取委托令牌后再调用");
        }
        Map<String, Object> claims;
        try {
            claims = link.verify(token, AUDIENCE);
        } catch (RuntimeException e) {
            throw unverifiedRejection(token);
        }
        Object act = claims.get("act");
        Object actor = act instanceof Map<?, ?> map ? map.get("sub") : null;
        if (!"delegated".equals(claims.get("kind")) || !(actor instanceof String agent)) {
            throw new BoError(401, "AGENT_IDENTITY_REQUIRED", "BO API 只接受同时带用户和 Agent 身份的委托令牌", "由平台做令牌交换");
        }
        CallerIdentity caller = new CallerIdentity(
                text(claims.get("tid")),
                text(claims.get("sub")),
                agent,
                text(claims.get("agent_version")),
                "delegated".equals(claims.get("mode")) ? "delegated" : "on-behalf",
                claims.get("delegation_id") instanceof String id ? id : null);
        try {
            return checkCaller(caller, text(claims.get("scope")), operation);
        } catch (BoError error) {
            // 令牌已验签：之后的拒绝都带上调用方身份，推送到该租户的审计
            throw error.withCaller(caller);
        }
    }

    /** 验签失败时，按未验签的声明给出更具体的原因：用户令牌和集成账号令牌都没有 Agent 身份 */
    private static BoError unverifiedRejection(String token) {
        Map<String, Object> unverified = Map.of();
        try {
            unverified = JWTParser.parse(token).getJWTClaimsSet().toJSONObject();
        } catch (Exception e) {
            // 不是 JWT
        }
        if ("user".equals(unverified.get("kind"))) {
            return new BoError(401, "AGENT_IDENTITY_REQUIRED", "用户令牌不能直接调用 BO API", "由平台以“用户 + Agent”身份做令牌交换，换取委托令牌");
        }
        Object audience = unverified.get("aud");
        boolean openAudience = "demo-erp-open".equals(audience)
                || (audience instanceof List<?> list && list.size() == 1 && "demo-erp-open".equals(list.getFirst()));
        if ("client".equals(unverified.get("kind")) || openAudience) {
            return new BoError(
                    401, "AGENT_IDENTITY_REQUIRED", "集成账号没有人的身份，不能调用 BO API", "系统集成请改用 Open API；Agent 必须以用户身份换取委托令牌");
        }
        return new BoError(401, "INVALID_TOKEN", "委托令牌无效或已过期", "重新换取委托令牌");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 令牌验签之后的检查：租户、业务权限、Agent 声明、委托、数据权限 */
    private BoContext checkCaller(CallerIdentity caller, String scopeClaim, String operation) {
        Optional<DemoUser> found = DemoDirectory.findUser(caller.userId());
        if (found.isEmpty() || !found.get().tenantId().equals(caller.tenantId())) {
            throw new BoError(403, "TENANT_MISMATCH", "令牌中的用户不属于该租户", "确认登录的租户");
        }
        DemoUser user = found.get();
        if (!DemoDirectory.permittedOperations(user).contains(operation)) {
            throw new BoError(
                    403,
                    "BUSINESS_PERMISSION_DENIED",
                    "用户 " + user.name() + " 在 ERP 里没有 " + operation + " 的权限",
                    "用户在 ERP 里做不了的事，通过 Agent 也做不了；请联系管理员授权");
        }
        if (!Arrays.asList(scopeClaim.split(" ")).contains(operation)) {
            throw new BoError(
                    403, "AGENT_SCOPE_DENIED", caller.agent() + " 没有声明 " + operation, "在 Agent 定义里声明该工具后重新发布");
        }
        String mode = caller.mode();
        var spec = BoTypes.OPERATIONS.get(operation);
        // 受托自主运行没有在线的人确认，BO API 只允许只读操作（硬约束写在 API 里，不只靠平台策略）
        if (mode.equals("delegated") && !spec.writeLevel().equals("L0")) {
            throw new BoError(403, "DELEGATED_WRITE_FORBIDDEN", "受托自主运行不能执行写操作 " + operation, "改为生成建议，由用户在线确认后执行");
        }
        String delegationId = caller.delegationId();
        if (mode.equals("delegated") && (delegationId == null || !link.delegationActive(delegationId))) {
            throw new BoError(
                    403,
                    "DELEGATION_REVOKED",
                    "委托 " + (delegationId == null ? "" : delegationId) + " 已撤销或失效",
                    "由租户管理员重新登记委托");
        }
        return new BoContext(
                user.tenantId(),
                user,
                caller.agent(),
                caller.agentVersion(),
                mode,
                delegationId,
                operation,
                dataScopeOf(user, spec.dimension()));
    }

    static Scope dataScopeOf(DemoUser user, Dimension dimension) {
        Optional<Tenant> tenant = DemoDirectory.findTenant(user.tenantId());
        if (dimension == Dimension.none || tenant.isEmpty()) {
            String name = tenant.map(Tenant::name).orElse(user.tenantId());
            return new Scope(dimension, List.of(), List.of(), "租户：" + name, false);
        }
        List<Organization> all = dimension == Dimension.purchasingOrg
                ? tenant.get().purchasingOrgs()
                : tenant.get().companies();
        List<String> values = dimension == Dimension.purchasingOrg
                ? List.copyOf(user.dataScope().purchasingOrgs())
                : List.copyOf(user.dataScope().companies());
        List<String> names = all.stream()
                .filter(o -> values.contains(o.id()))
                .map(Organization::name)
                .toList();
        String label = dimension.label + "：" + (names.isEmpty() ? "无" : String.join("、", names));
        return new Scope(dimension, values, names, label, values.size() < all.size());
    }
}
