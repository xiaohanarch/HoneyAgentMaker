package dev.eab.platform.policy;

import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.http.Principals;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.policy.PolicyEngine;
import dev.eab.policy.PolicyEngine.Decision;
import dev.eab.policy.PolicyEngine.PolicyRuleSet;
import dev.eab.policy.PolicyInputs;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 策略规则查看与试算（治理中心）：以当前用户身份对鉴权点 1 至 3 做一次判定，结果写入审计 */
@RestController
@Tag(name = "policy")
public class PolicyController {

    private static final Pattern REF = Pattern.compile("^[a-z0-9.-]+/[a-z0-9-]+$");

    private final PlatformServices services;

    public PolicyController(PlatformServices services) {
        this.services = services;
    }

    /**
     * 每一次裁决写入审计（主提示词 7.3 节）。策略试算的事实由调用方提供，审计里与真实运行的裁决分开记录。
     */
    public static void recordDecision(
            AuditStore audit, Decision decision, boolean trial, String actor, String tenantId, String userId,
            String target, String agent, String tool, String mode) {
        audit.append(NewAuditEvent.of(actor, (trial ? "policy.trial" : "policy") + "." + decision.checkpoint(), target,
                        decision.outcome(), PolicyEngine.CHECKPOINT_LABELS.get(decision.checkpoint()) + "：" + decision.reason())
                .tenantId(tenantId)
                .userId(userId)
                .checkpoint(decision.checkpoint())
                .ruleId(decision.ruleId())
                .agent(agent)
                .tool(tool)
                .mode(mode)
                .detail(Map.of("rulesVersion", decision.rulesVersion(), "requirements", decision.requirements())));
    }

    @GetMapping("/policy/v1/rules")
    @Operation(summary = "当前生效的策略规则（治理中心查看）")
    public ApiResponse<PolicyRuleSet> rules() {
        return ApiResponse.success(services.policyRules());
    }

    public record CallerBody(String type, String ref, Integer depth) {}

    public record DocumentBody(String id, String action, String submittedBy) {}

    /** 三种鉴权点的试算请求合在一个结构里，按 checkpoint 取用对应字段 */
    public record EvaluateBody(
            String checkpoint,
            String assistant,
            String target,
            CallerBody caller,
            String agent,
            String tool,
            Map<String, Object> toolInput,
            String mode,
            String delegationId,
            DocumentBody document) {}

    @PostMapping("/policy/v1/evaluate")
    @Operation(summary = "策略试算：以当前用户身份对鉴权点 1 至 3 做一次判定，结果写入审计")
    public ApiResponse<Decision> evaluate(HttpServletRequest request, @RequestBody EvaluateBody body) {
        var principal = services.principals().require(request);
        if (!(principal.viewer() instanceof Viewer.Tenant tenant)) {
            throw new ApiException(403, "TENANT_ONLY", "策略试算以租户用户身份进行");
        }
        if (body == null || body.checkpoint() == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少 checkpoint");
        String tenantId = tenant.tenantId();
        var subjectFacts = Principals.subjectFacts(principal.user());
        var subject = new PolicyInputs.SubjectFacts(
                subjectFacts.userId(), subjectFacts.tenantId(), subjectFacts.roles(), subjectFacts.operations());
        var settings = services.tenantSettings().get(tenantId);

        Decision decision;
        String target;
        String agent = null;
        String tool = null;
        String mode = null;
        switch (body.checkpoint()) {
            case "entry" -> {
                requireRef(body.assistant(), true);
                target = body.assistant() == null ? "统一入口" : body.assistant();
                decision = PolicyEngine.evaluate(services.policyRules(), "entry", PolicyInputs.entry(
                        subject,
                        tenantId,
                        body.assistant() == null
                                ? null
                                : new PolicyInputs.Assistant(body.assistant(),
                                        settings.assistantRoles().getOrDefault(body.assistant(), List.of()))),
                        services.clock().instant());
            }
            case "handoff" -> {
                requireRef(body.target(), false);
                target = body.target();
                var caller = body.caller() == null ? new CallerBody("router", "router", 0) : body.caller();
                if (!List.of("router", "assistant", "agent", "external").contains(caller.type())
                        || caller.depth() == null || caller.depth() < 0 || caller.ref() == null) {
                    throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：caller");
                }
                Asset asset = services.registry().resolverFor(principal.viewer()).resolve(body.target()).orElse(null);
                String kind = asset instanceof Asset.RemoteAgentAsset ? "RemoteAgent"
                        : asset instanceof Asset.AssistantAsset ? "Assistant" : "Agent";
                List<String> allowedCallers = asset instanceof Asset.RemoteAgentAsset remote
                        ? remote.spec().allowedCallers().stream().map(c -> AssetRef.parse(c).key()).toList()
                        : List.of();
                decision = PolicyEngine.evaluate(services.policyRules(), "handoff", PolicyInputs.handoff(
                                subject,
                                new PolicyInputs.Caller(caller.type(), caller.ref(), caller.depth()),
                                new PolicyInputs.HandoffTarget(body.target(), kind, asset != null, allowedCallers)),
                        services.clock().instant());
            }
            case "tool" -> {
                requireRef(body.agent(), false);
                if (body.tool() == null) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：缺少 tool");
                mode = body.mode() == null ? "on-behalf" : body.mode();
                if (!List.of("on-behalf", "delegated", "integration").contains(mode)) {
                    throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：mode");
                }
                target = body.agent() + " → " + body.tool();
                var outcome = EffectiveAssembly.assemble(
                        services.registry(), principal.viewer(), EffectiveAssembly.Request.of(body.agent(), tenantId));
                if (!(outcome instanceof EffectiveAssembly.Outcome.Ok ok)) {
                    throw new ApiException(422, new ApiError("ASSEMBLY_FAILED", "组装失败", outcome.issues(), null));
                }
                // 只认本租户、仍然有效的委托；其他租户的委托编号按不存在处理，不回显
                PolicyInputs.Delegation delegation = null;
                if (body.delegationId() != null) {
                    var status = services.delegations().status(body.delegationId());
                    if (status.active() && status.delegation().tenantId().equals(tenantId)) {
                        delegation = new PolicyInputs.Delegation(status.delegation().id(), status.delegation().scope());
                    }
                }
                var document = body.document() == null
                        ? null
                        : new PolicyInputs.Document(body.document().id(), body.document().action(), body.document().submittedBy());
                decision = PolicyEngine.evaluate(services.policyRules(), "tool", PolicyInputs.tool(new PolicyInputs.ToolContext(
                                subject, mode, ok.definition(), body.tool(), body.toolInput(), settings.l3Enabled(), delegation, document)),
                        services.clock().instant());
                agent = ok.definition().provenance().agent();
                tool = body.tool();
            }
            default -> throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：checkpoint 只能是 entry、handoff、tool");
        }
        recordDecision(services.audit(), decision, true, principal.user().id(), tenantId, principal.user().id(), target, agent, tool, mode);
        return ApiResponse.success(decision);
    }

    private static void requireRef(String ref, boolean optional) {
        if (ref == null && optional) return;
        if (ref == null || !REF.matcher(ref).matches()) throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：引用写作 namespace/name");
    }
}
