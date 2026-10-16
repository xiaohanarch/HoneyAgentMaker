package dev.eab.runtime.routing;

import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AssistantAsset;
import dev.eab.spec.model.Asset.RemoteAgentAsset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.model.Common.Routing;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 能力目录（主提示词 9.1 节）：路由的候选来自已发布的资产，发布即生效，不需要另外登记。
 * 每个条目是一个可以被路由到的场景：岗位助手、Agent，以及平台自带的导航场景（打开 DemoERP 页面）。
 */
public final class Catalog {

    private Catalog() {}

    /**
     * 目录里的一个条目。
     *
     * @param kind assistant、agent、remote（外部 A2A Agent，执行动作类型 agent-request）、navigation
     * @param ref namespace/name；导航场景为 platform/navigation:&lt;页面&gt;
     * @param examples 示例话术，Agent 还包括挂载语义包里的 verified_tasks
     * @param members 岗位助手编排的 Agent
     * @param direct Agent 是否可以由入口直达，不经过岗位助手
     */
    public record Entry(
            String kind,
            String ref,
            String version,
            String displayName,
            String description,
            List<String> examples,
            List<String> members,
            boolean direct) {

        /** 召回用的文本：路由说明和每条示例话术 */
        public List<String> texts() {
            List<String> texts = new ArrayList<>();
            if (description != null) texts.add(description);
            texts.addAll(examples);
            return texts;
        }
    }

    /** 导航型交互（主提示词 9.1.1 节）：打开 DemoERP 的只读页面，返回深链接卡片 */
    public static final List<Entry> NAVIGATION = List.of(
            new Entry(
                    "navigation",
                    "platform/navigation:supplier",
                    "1.0.0",
                    "打开供应商详情页",
                    "打开、查看某个供应商的详情页面",
                    List.of("打开百润精密的供应商详情", "查看供应商详情页", "打开供应商页面"),
                    List.of(),
                    false),
            new Entry(
                    "navigation",
                    "platform/navigation:purchase-order",
                    "1.0.0",
                    "打开采购订单页面",
                    "打开、查看某张采购订单的页面",
                    List.of("打开 PO-2026-0117 的订单页面", "查看这张采购订单的详情页", "打开订单详情"),
                    List.of(),
                    false));

    private static String ref(Asset asset) {
        return asset.metadata().namespace() + "/" + asset.metadata().name();
    }

    private static String name(Asset asset) {
        return asset.metadata().displayName() == null
                ? asset.metadata().name()
                : asset.metadata().displayName();
    }

    /** Agent 直接挂载的工具名：技能名、API 工具名、内置工具名 */
    static Set<String> toolNames(AgentAsset agent) {
        Set<String> names = new HashSet<>();
        for (var entry : agent.spec().tools()) {
            var tool = entry.normalize();
            switch (tool.type()) {
                case "api", "mcp" -> {
                    if (tool.tools() != null) names.addAll(tool.tools());
                }
                case "builtin" -> names.add(tool.builtinName());
                default -> {
                    String ref = tool.ref();
                    String bare = ref.contains("@") ? ref.substring(0, ref.indexOf('@')) : ref;
                    names.add(bare.substring(bare.indexOf('/') + 1));
                }
            }
        }
        return names;
    }

    /**
     * 从已发布的资产生成目录。Agent 的示例话术合并它挂载的语义包里、它有工具完成的 verified_tasks（一份资产三种用途）。
     */
    public static List<Entry> build(List<Asset> published) {
        Map<String, SemanticPackAsset> packs = new LinkedHashMap<>();
        for (Asset asset : published) {
            if (asset instanceof SemanticPackAsset pack) packs.put(ref(pack), pack);
        }
        List<Entry> entries = new ArrayList<>();
        for (Asset asset : published) {
            switch (asset) {
                case AssistantAsset assistant -> {
                    Routing routing = assistant.spec().routing();
                    entries.add(new Entry(
                            "assistant",
                            ref(assistant),
                            assistant.metadata().version(),
                            name(assistant),
                            routing == null ? null : routing.description(),
                            routing == null || routing.examples() == null ? List.of() : routing.examples(),
                            assistant.spec().members() == null
                                    ? List.of()
                                    : assistant.spec().members(),
                            false));
                }
                case AgentAsset agent -> {
                    Routing routing = agent.spec().routing();
                    List<String> examples = new ArrayList<>(
                            routing == null || routing.examples() == null ? List.of() : routing.examples());
                    for (String packRef : agent.spec().semanticPacks() == null
                            ? List.<String>of()
                            : agent.spec().semanticPacks()) {
                        SemanticPackAsset pack =
                                packs.get(packRef.contains("@") ? packRef.substring(0, packRef.indexOf('@')) : packRef);
                        if (pack == null || pack.spec().verifiedTasks() == null) continue;
                        Set<String> tools = toolNames(agent);
                        // 只合并这个 Agent 有能力完成的任务：同一个语义包常被多个 Agent 挂载，否则它们的示例会互相混淆
                        pack.spec().verifiedTasks().stream()
                                .filter(t -> t.tools() == null || tools.containsAll(t.tools()))
                                .forEach(t -> examples.add(t.question()));
                    }
                    entries.add(new Entry(
                            "agent",
                            ref(agent),
                            agent.metadata().version(),
                            name(agent),
                            routing == null ? agent.spec().expertise() : routing.description(),
                            examples,
                            List.of(),
                            routing != null && routing.direct()));
                }
                case RemoteAgentAsset remote -> {
                    Routing routing = remote.spec().routing();
                    entries.add(new Entry(
                            "remote",
                            ref(remote),
                            remote.metadata().version(),
                            name(remote),
                            routing == null ? null : routing.description(),
                            routing == null || routing.examples() == null ? List.of() : routing.examples(),
                            List.of(),
                            remote.spec().assistant() == null || (routing != null && routing.direct())));
                }
                default -> {}
            }
        }
        return withRemoteMembers(entries, published);
    }

    /** 远程 Agent 声明了所属岗位助手时，加进该助手的成员，第二级路由可以选到它 */
    static List<Entry> withRemoteMembers(List<Entry> entries, List<Asset> published) {
        Map<String, List<String>> extra = new LinkedHashMap<>();
        for (Asset asset : published) {
            if (asset instanceof RemoteAgentAsset remote && remote.spec().assistant() != null) {
                String assistant = remote.spec().assistant();
                String bare = assistant.contains("@") ? assistant.substring(0, assistant.indexOf('@')) : assistant;
                extra.computeIfAbsent(bare, k -> new ArrayList<>()).add(ref(remote));
            }
        }
        if (extra.isEmpty()) return entries;
        List<Entry> result = new ArrayList<>();
        for (Entry e : entries) {
            List<String> more = e.kind().equals("assistant") ? extra.get(e.ref()) : null;
            if (more == null) {
                result.add(e);
                continue;
            }
            List<String> members = new ArrayList<>(e.members());
            more.stream().filter(m -> !members.contains(m)).forEach(members::add);
            result.add(new Entry(
                    e.kind(),
                    e.ref(),
                    e.version(),
                    e.displayName(),
                    e.description(),
                    e.examples(),
                    members,
                    e.direct()));
        }
        return result;
    }
}
