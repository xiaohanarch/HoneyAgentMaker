package dev.eab.platform.identity;

import com.nimbusds.jwt.JWTClaimsSet;
import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.audit.AuditStore;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.identity.TokenService.TokenException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟身份服务（主提示词 7.1 节）：选人登录、客户端凭证、RFC 8693 令牌交换。
 * 令牌交换失败一律抛 TokenException，绝不退回到集成账号或其他技术身份（主提示词 8.4 节）。
 */
public final class IdentityService {

    public static final class TokenType {
        private TokenType() {}

        public static final String JWT = "urn:ietf:params:oauth:token-type:jwt";
        public static final String ACCESS_TOKEN = "urn:ietf:params:oauth:token-type:access_token";
        /** 受托自主运行：subject_token 写 delegation:&lt;委托编号&gt; */
        public static final String DELEGATION = "urn:eab:params:oauth:token-type:delegation";
    }

    /** @param scope 可选：只申请部分操作，null 表示不限 */
    public record ExchangeRequest(
            String subjectToken, String subjectTokenType, String actorToken, String audience, List<String> scope) {}

    /** @param claims 委托令牌的声明：sub、act、tid、scope、mode、delegation_id、agent_version */
    public record ExchangeResult(String accessToken, List<String> scope, long expiresIn, Map<String, Object> claims) {}

    public record Login(String token, DemoUser user) {}

    private final TokenService tokens;
    private final DelegationStore delegations;
    private final ClientRegistry clients;
    private final AuditStore audit;

    public IdentityService(TokenService tokens, DelegationStore delegations, ClientRegistry clients, AuditStore audit) {
        this.tokens = tokens;
        this.delegations = delegations;
        this.clients = clients;
        this.audit = audit;
    }

    public Login login(String userId) {
        DemoUser user = DemoDirectory.findUser(userId)
                .orElseThrow(() -> new TokenException("UNKNOWN_USER", "用户 " + userId + " 不存在"));
        return new Login(tokens.issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles())), user);
    }

    public String clientCredentials(String clientId, String secret) {
        var client = clients.authenticate(clientId, secret)
                .orElseThrow(() -> new TokenException("INVALID_CLIENT", "集成账号或密钥错误"));
        return tokens.issueClient("client:" + client.id(), client.tenantId());
    }

    /** 平台运行时在每次运行前为 Agent 签发身份，携带它声明的 BO 操作 */
    public String issueAgentToken(String agent, String version, String tenantId, List<String> operations) {
        return tokens.issueAgent(new TokenService.AgentClaims("agent:" + agent, tenantId, String.join(" ", operations), version));
    }

    /** 交换过程中逐步确认的身份（Agent 令牌验签后才写入），拒绝时用它把审计记到对应租户 */
    private static final class Known {
        String tenantId;
        String agent;
        String userId;
        String mode;
    }

    /**
     * 令牌交换：以“用户 + Agent”双重身份换取委托令牌。
     * 有效权限 = 用户的业务权限 ∩ Agent 声明的操作 ∩ 委托范围（受托运行时），再与申请范围取交集。
     */
    public ExchangeResult exchange(ExchangeRequest request) {
        Known known = new Known();
        try {
            ExchangeResult result = doExchange(request, known);
            Map<String, Object> c = result.claims();
            @SuppressWarnings("unchecked")
            String actor = ((Map<String, Object>) c.get("act")).get("sub").toString();
            boolean delegated = "delegated".equals(c.get("mode"));
            audit.append(NewAuditEvent.of(actor, "identity.token-exchange", TokenService.Audience.BO, "allow",
                            "签发委托令牌：" + (delegated ? "受托运行（" + c.getOrDefault("delegation_id", "") + "）" : "代用户操作"))
                    .tenantId((String) c.get("tid"))
                    .userId((String) c.get("sub"))
                    .agent(actor.replaceFirst("^agent:", "") + "@" + c.get("agent_version"))
                    .mode((String) c.get("mode"))
                    .resultSummary("授权操作 " + result.scope().size() + " 个"));
            return result;
        } catch (RuntimeException error) {
            TokenException tokenError = error instanceof TokenException t
                    ? t
                    : new TokenException("EXCHANGE_FAILED", String.valueOf(error.getMessage()));
            String target = request.audience() == null ? "" : request.audience();
            audit.append(NewAuditEvent.of("identity", "identity.token-exchange",
                            target.substring(0, Math.min(64, target.length())), "deny",
                            truncate(tokenError.getMessage(), 300))
                    .ruleId(tokenError.code())
                    .resultSummary("令牌交换失败，按失败即拒绝处理，未退回任何技术账号")
                    .tenantId(known.tenantId)
                    .agent(known.agent)
                    .userId(known.userId)
                    .mode(known.mode));
            throw tokenError;
        }
    }

    private static String truncate(String text, int max) {
        return text == null ? "" : text.substring(0, Math.min(max, text.length()));
    }

    private ExchangeResult doExchange(ExchangeRequest request, Known known) {
        if (!TokenService.Audience.BO.equals(request.audience())) {
            throw new TokenException("INVALID_TARGET", "不支持换取受众为 " + request.audience() + " 的令牌");
        }
        var actor = tokens.verifyAgent(request.actorToken());
        String agentRef = actor.sub().replaceFirst("^agent:", "");
        known.tenantId = actor.tid();
        known.agent = agentRef + "@" + actor.agentVersion();

        DemoUser user;
        DelegationStore.Delegation delegation = null;
        if (TokenType.DELEGATION.equals(request.subjectTokenType())) {
            String id = request.subjectToken().replaceFirst("^delegation:", "");
            known.mode = "delegated";
            var status = delegations.status(id);
            if (status.delegation() != null && status.delegation().tenantId().equals(actor.tid())) {
                known.userId = status.delegation().delegatorUserId();
            }
            if (!status.active()) throw new TokenException("DELEGATION_INACTIVE", "委托 " + id + " 不可用：" + status.reason());
            delegation = status.delegation();
            if (!delegation.agent().equals(agentRef)) {
                throw new TokenException("DELEGATION_AGENT_MISMATCH",
                        "委托 " + id + " 授权的是 " + delegation.agent() + "，不是 " + agentRef);
            }
            if (!delegation.tenantId().equals(actor.tid())) throw new TokenException("TENANT_MISMATCH", "委托与 Agent 不属于同一租户");
            user = DemoDirectory.findUser(delegation.delegatorUserId()).orElse(null);
        } else if (TokenType.JWT.equals(request.subjectTokenType()) || TokenType.ACCESS_TOKEN.equals(request.subjectTokenType())) {
            known.mode = "on-behalf";
            var subject = tokens.verifyUser(request.subjectToken());
            if (subject.tid().equals(actor.tid())) known.userId = subject.sub();
            if (!subject.tid().equals(actor.tid())) throw new TokenException("TENANT_MISMATCH", "用户与 Agent 不属于同一租户");
            user = DemoDirectory.findUser(subject.sub()).orElse(null);
        } else {
            throw new TokenException("UNSUPPORTED_SUBJECT", "不支持的 subject_token_type：" + request.subjectTokenType());
        }
        if (user == null) throw new TokenException("UNKNOWN_USER", "委托令牌的主体用户不存在");

        List<String> actorScope = List.of(actor.scope() == null ? new String[0] : actor.scope().split(" "));
        List<String> scope = new ArrayList<>(DemoDirectory.permittedOperations(user));
        scope.retainAll(actorScope);
        if (delegation != null) scope.retainAll(delegation.scope());
        if (request.scope() != null) scope.retainAll(request.scope());
        if (scope.isEmpty()) {
            throw new TokenException("EMPTY_SCOPE", "用户 " + user.id() + " 与 " + agentRef + " 的权限交集为空，不签发令牌");
        }

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", user.id());
        claims.put("act", Map.of("sub", actor.sub()));
        claims.put("tid", actor.tid());
        claims.put("scope", String.join(" ", scope));
        claims.put("mode", delegation == null ? "on-behalf" : "delegated");
        if (delegation != null) claims.put("delegation_id", delegation.id());
        claims.put("agent_version", actor.agentVersion());
        return new ExchangeResult(tokens.issueDelegated(claims), List.copyOf(scope), TokenService.DELEGATED_TTL, claims);
    }

    /** 解析令牌声明，供测试和演示控制台查看 */
    public static Map<String, Object> decode(String token) {
        try {
            JWTClaimsSet claims = com.nimbusds.jwt.SignedJWT.parse(token).getJWTClaimsSet();
            return claims.toJSONObject();
        } catch (java.text.ParseException e) {
            throw new IllegalArgumentException("不是 JWT", e);
        }
    }
}
