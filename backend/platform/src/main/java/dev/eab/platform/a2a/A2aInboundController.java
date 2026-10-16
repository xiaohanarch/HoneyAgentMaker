package dev.eab.platform.a2a;

import dev.eab.a2a.A2aServer;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.Principals;
import io.swagger.v3.oas.annotations.Hidden;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 入站 A2A 的 HTTP 端点：Agent Card 在标准路径和旧路径各一份，JSON-RPC 请求必须带平台签发的用户令牌。
 * 没有身份的请求一律拒绝并写审计，不会以任何技术账号执行。
 */
@Hidden
@RestController
public class A2aInboundController {

    /** 调用方自报的平台名，只写进审计，不参与鉴权 */
    public static final String CALLER_HEADER = "X-A2A-Caller";

    static final String AUDIT_ACTION = "a2a.inbound";

    private final PlatformServices services;
    private final Principals principals;
    private final InboundAgents agents;

    public A2aInboundController(PlatformServices services, Principals principals, InboundAgents agents) {
        this.services = services;
        this.principals = principals;
        this.agents = agents;
    }

    private InboundAgents.Exposed exposed(String namespace, String name) {
        return agents.find(namespace, name)
                .orElseThrow(() ->
                        new ApiException(404, "A2A_NOT_EXPOSED", namespace + "/" + name + " 不存在、未发布或没有允许被外部 Agent 调用"));
    }

    @GetMapping(value = "/a2a/agents/{namespace}/{name}/.well-known/agent-card.json", produces = "application/json")
    public String card(@PathVariable String namespace, @PathVariable String name) {
        return agents.server(exposed(namespace, name)).cardJson();
    }

    /** 旧路径，0.3 版本客户端从这里取 Agent Card */
    @GetMapping(value = "/a2a/agents/{namespace}/{name}/.well-known/agent.json", produces = "application/json")
    public String legacyCard(@PathVariable String namespace, @PathVariable String name) {
        return agents.server(exposed(namespace, name)).legacyCardJson();
    }

    @PostMapping("/a2a/agents/{namespace}/{name}")
    public ResponseEntity<?> call(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = CALLER_HEADER, required = false) String caller,
            @RequestHeader(value = A2aServer.VERSION_HEADER, required = false) String version,
            @RequestBody String body) {
        InboundAgents.Exposed target = exposed(namespace, name);
        Principals.Principal principal;
        try {
            principal = principals.verify(authorization);
        } catch (ApiException e) {
            services.audit()
                    .append(NewAuditEvent.of(
                                    "anonymous",
                                    AUDIT_ACTION,
                                    target.ref(),
                                    "deny",
                                    "身份透传失败：" + e.error().message() + "（调用方 " + label(caller) + "）")
                            .checkpoint("entry"));
            throw e;
        }
        var user = principal.user();
        if (namespace.startsWith("tenant.") && !namespace.startsWith("tenant." + user.tenantId() + ".")) {
            services.audit()
                    .append(NewAuditEvent.of(user.id(), AUDIT_ACTION, target.ref(), "deny", "不能调用其他租户的 Agent")
                            .tenantId(user.tenantId())
                            .checkpoint("entry"));
            throw new ApiException(403, "A2A_TENANT_MISMATCH", "不能调用其他租户的 Agent");
        }
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(),
                                AUDIT_ACTION,
                                target.ref(),
                                "allow",
                                "外部调用方 " + label(caller) + " 以用户 " + user.id() + " 的身份经 A2A 调用")
                        .tenantId(user.tenantId())
                        .userId(user.id())
                        .agent(target.ref())
                        .checkpoint("entry")
                        .detail(Map.of("caller", label(caller), "kind", target.kind())));
        var context = A2aServer.context(
                user.id(),
                Map.of(A2aServer.PRINCIPAL_KEY, new InboundRunner.Caller(principal, label(caller))),
                version);
        A2aServer.Response response = agents.server(target).handle(body, context);
        if (response instanceof A2aServer.Response.Json json) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json.body());
        }
        var stream = (A2aServer.Response.Stream) response;
        StreamingResponseBody out = output -> pipe(stream.events(), output);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(out);
    }

    static String label(String caller) {
        if (caller == null || caller.isBlank()) return "未声明";
        String trimmed = caller.strip();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    /** 把 SSE 事件逐条写出，直到流结束或调用方断开 */
    static void pipe(Flow.Publisher<String> events, OutputStream output) {
        CountDownLatch done = new CountDownLatch(1);
        events.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String item) {
                try {
                    output.write(item.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                } catch (IOException e) {
                    subscription.cancel();
                    done.countDown();
                }
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
