package dev.eab.platform.assist;

import dev.eab.directory.DemoDirectory;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.events.EventStore.BusinessEvent;
import dev.eab.platform.identity.DelegationStore;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset.AgentAsset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务事件触发（主提示词 8.6 节）：事件到达后，找出本租户已发布、声明了这个事件触发器的 Agent，
 * 以登记过的有效委托运行（受托自主运行，只读），结果推送到委托人的通知区。没有有效委托的 Agent 不运行，并写审计，
 * 不会退回到技术账号。
 */
public final class EventTriggers {

    private static final Logger LOG = LoggerFactory.getLogger(EventTriggers.class);

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final NotificationStore notifications;

    public EventTriggers(PlatformServices services, RuntimeService runtime, NotificationStore notifications) {
        this.services = services;
        this.runtime = runtime;
        this.notifications = notifications;
    }

    /** 订阅业务事件；运行在虚拟线程上，不阻塞事件接收接口 */
    public Runnable subscribe() {
        return services.events()
                .subscribe(event -> Thread.ofVirtual()
                        .name("trigger-" + event.id())
                        .start(() -> {
                            try {
                                handle(event);
                            } catch (RuntimeException e) {
                                LOG.error("业务事件 {} 触发失败", event.type(), e);
                            }
                        }));
    }

    /** 事件交给 Agent 时的话术，剧本和真实模型都按它理解事件 */
    static String message(BusinessEvent event) {
        Map<String, Object> p = event.payload();
        return switch (event.type()) {
            case "delivery.overdue" ->
                "交付逾期事件：采购订单 " + p.get("poId") + "（" + p.get("supplierName") + "）已逾期 " + p.get("overdueDays")
                        + " 天，涉及物料 " + p.get("materialId") + "。请评估对库存的影响。";
            case "inventory.below_safety_stock" ->
                "库存预警事件：物料 " + p.get("materialId") + " " + p.get("materialName") + " 可用 " + p.get("quantity")
                        + "，低于安全库存 " + p.get("safetyStock") + "。请分析原因并给出处置建议。";
            default -> "业务事件 " + event.type() + "：" + p;
        };
    }

    /** 同步处理一个事件，测试直接调用 */
    public List<String> handle(BusinessEvent event) {
        var agents = services
                .registry()
                .latestPublished(new AssetFilter("Agent", null, null, null, null), Viewer.tenant(event.tenantId()))
                .assets()
                .stream()
                .filter(AgentAsset.class::isInstance)
                .map(AgentAsset.class::cast)
                .filter(a -> a.spec().triggers() != null
                        && a.spec().triggers().stream()
                                .anyMatch(t ->
                                        "event".equals(t.type()) && event.type().equals(t.event())))
                .toList();
        List<String> started = new java.util.ArrayList<>();
        for (AgentAsset agent : agents) {
            String ref = agent.metadata().namespace() + "/" + agent.metadata().name();
            DelegationStore.Delegation delegation = services.delegations().listByTenant(event.tenantId()).stream()
                    .filter(d -> d.agent().equals(ref)
                            && services.delegations().status(d.id()).active())
                    .findFirst()
                    .orElse(null);
            if (delegation == null) {
                services.audit()
                        .append(NewAuditEvent.of(
                                        "platform/trigger",
                                        "trigger.event",
                                        ref,
                                        "deny",
                                        "业务事件 " + event.type() + " 没有触发 " + ref + "：没有有效的委托，受托运行不会退回到技术账号")
                                .tenantId(event.tenantId())
                                .mode("delegated"));
                continue;
            }
            var delegator = DemoDirectory.findUser(delegation.delegatorUserId()).orElse(null);
            if (delegator == null) continue;
            String title = displayName(agent, ref) + " · " + event.type();
            try {
                started.add(runDelegated(event, ref, title, delegation, delegator));
            } catch (RuntimeException e) {
                // 一个 Agent 失败不影响同一事件触发的其他 Agent；委托人能在通知区看到失败
                LOG.error("业务事件 {} 触发 {} 失败", event.type(), ref, e);
                services.audit()
                        .append(NewAuditEvent.of(
                                        "platform/trigger",
                                        "trigger.event",
                                        ref,
                                        "error",
                                        "业务事件 " + event.type() + " 触发 " + ref + " 时运行失败")
                                .tenantId(event.tenantId())
                                .mode("delegated"));
                notifications.add(
                        event.tenantId(),
                        delegator.id(),
                        title,
                        "运行失败，请联系管理员查看平台日志。",
                        "event:" + event.type(),
                        null,
                        null);
            }
        }
        return started;
    }

    private static String displayName(AgentAsset agent, String ref) {
        return agent.metadata().displayName() == null ? ref : agent.metadata().displayName();
    }

    private String runDelegated(
            BusinessEvent event,
            String ref,
            String title,
            DelegationStore.Delegation delegation,
            DemoDirectory.DemoUser delegator) {
        var outcome = runtime.start(
                new RuntimeService.StartRequest(
                        delegator, null, ref, message(event), "evt-" + event.id(), delegation.id()),
                e -> {});
        var result = outcome.result();
        String body = result.status().equals("completed")
                ? result.text()
                : "运行没有完成：" + (result.error() == null ? result.status() : result.error());
        notifications.add(
                event.tenantId(),
                delegator.id(),
                title,
                body,
                "event:" + event.type(),
                outcome.runId(),
                outcome.traceId());
        return outcome.runId();
    }
}
