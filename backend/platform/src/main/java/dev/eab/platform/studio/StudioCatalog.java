package dev.eab.platform.studio;

import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.RegistryService;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.ConnectionAsset;
import dev.eab.spec.model.Asset.KnowledgeSourceAsset;
import dev.eab.spec.model.Asset.RemoteAgentAsset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.model.Asset.SkillAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import java.util.ArrayList;
import java.util.List;

/**
 * 向导推荐用的能力清单（主提示词 10.2 节）：本租户可见的已发布 Agent、远程 Agent、语义包（含规则与已验证任务）、知识源、
 * 技能、工具，以及流程目录、渠道和模型档位。每一项带一段检索用的文本。
 */
public record StudioCatalog(
        List<Process> processes,
        List<Item> agents,
        List<Pack> semanticPacks,
        List<Item> knowledge,
        List<Item> skills,
        List<Tool> tools,
        List<Option> channels,
        List<Option> profiles) {

    public record Process(String code, String name, String description) {}

    /**
     * @param available 只有连接别名、还没有登记远程 Agent 资产的外部 Agent 不可用，推荐时标注原因
     */
    public record Item(
            String ref,
            String name,
            String description,
            String layer,
            String writeLevel,
            boolean available,
            String note,
            List<String> examples) {

        String text() {
            return name + " " + (description == null ? "" : description) + " " + String.join(" ", examples);
        }
    }

    public record Rule(String id, String severity, String description, String remedy, boolean checkable) {}

    public record VerifiedTask(
            String id, String question, String expectedConclusion, String failIf, List<String> tools) {}

    public record Pack(
            String ref,
            String name,
            String description,
            List<String> objects,
            List<Rule> rules,
            List<VerifiedTask> verifiedTasks) {

        String text() {
            return name + " " + (description == null ? "" : description) + " " + String.join(" ", objects) + " "
                    + String.join(
                            " ",
                            verifiedTasks.stream().map(VerifiedTask::question).toList());
        }
    }

    public record Tool(String source, String name, String description, String writeLevel, String category) {}

    public record Option(String value, String label, String description) {}

    /** 流程目录，与资产 metadata.businessProcess 的取值一致 */
    public static final List<Process> PROCESSES = List.of(
            new Process("source-to-pay", "S2P 寻源到付款", "供应商寻源、询价、谈判、合同、采购订单到付款"),
            new Process("procure-to-pay", "P2P 采购到付款", "请购、采购订单、收货、发票校验与付款"),
            new Process("plan-to-produce", "P2P 计划到生产", "需求计划、物料计划、生产与库存"),
            new Process("record-to-report", "R2R 记录到报告", "总账、结账、合并与财务报告"),
            new Process("order-to-cash", "O2C 订单到收款", "销售订单、交货、开票与收款"),
            new Process("hire-to-retire", "H2R 招聘到离职", "招聘、入职、薪酬与离职"));

    public static final List<Option> CHANNELS = List.of(
            new Option("assistant-web", "统一入口", "最终用户在浏览器里的对话入口"),
            new Option("im", "IM", "企业 IM 里的私聊与群消息"),
            new Option("api", "API", "由其他系统经平台接口调用"));

    public static final List<Option> PROFILES = List.of(
            new Option("fast", "fast", "响应快、成本低，适合查询与简单处理"),
            new Option("reasoning", "reasoning", "多步推理与综合判断，适合分析和策略类任务"),
            new Option("judge", "judge", "评测裁判使用"));

    static String refOf(Asset asset) {
        return asset.metadata().namespace() + "/" + asset.metadata().name();
    }

    static String nameOf(Asset asset) {
        return asset.metadata().displayName() == null
                ? asset.metadata().name()
                : asset.metadata().displayName();
    }

    public static StudioCatalog build(RegistryService registry, String tenantId) {
        var published = registry.latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
        List<Item> agents = new ArrayList<>();
        List<Pack> packs = new ArrayList<>();
        List<Item> knowledge = new ArrayList<>();
        List<Item> skills = new ArrayList<>();
        List<Tool> tools = new ArrayList<>();
        java.util.Set<String> remoteAliases = new java.util.HashSet<>();
        for (Asset asset : published) {
            if (asset instanceof RemoteAgentAsset r) remoteAliases.add(r.spec().connection());
        }
        for (Asset asset : published) {
            String layer = asset.layer().name();
            switch (asset) {
                case AgentAsset a ->
                    agents.add(new Item(
                            refOf(a),
                            nameOf(a),
                            a.metadata().description(),
                            layer,
                            // 子 Agent 作为工具时的写级别就是它的写级别上限
                            a.spec().writePolicy() == null
                                    ? null
                                    : a.spec().writePolicy().maxLevel(),
                            true,
                            null,
                            a.spec().routing() == null || a.spec().routing().examples() == null
                                    ? List.of()
                                    : a.spec().routing().examples()));
                case RemoteAgentAsset r ->
                    agents.add(new Item(
                            refOf(r),
                            nameOf(r),
                            r.spec().routing() != null && r.spec().routing().description() != null
                                    ? r.spec().routing().description()
                                    : r.metadata().description(),
                            "external",
                            null,
                            true,
                            "外部 Agent，经连接别名 " + r.spec().connection() + " 以 A2A 协议调用",
                            r.spec().routing() == null || r.spec().routing().examples() == null
                                    ? List.of()
                                    : r.spec().routing().examples()));
                case SemanticPackAsset p ->
                    packs.add(new Pack(
                            refOf(p),
                            nameOf(p),
                            p.metadata().description(),
                            p.spec().objects() == null
                                    ? List.of()
                                    : p.spec().objects().stream()
                                            .map(o -> o.ref())
                                            .toList(),
                            p.spec().rules() == null
                                    ? List.of()
                                    : p.spec().rules().stream()
                                            .map(r -> new Rule(
                                                    r.id(),
                                                    r.severity(),
                                                    r.description(),
                                                    r.remedy(),
                                                    r.check() != null))
                                            .toList(),
                            p.spec().verifiedTasks() == null
                                    ? List.of()
                                    : p.spec().verifiedTasks().stream()
                                            .map(t -> new VerifiedTask(
                                                    t.id(),
                                                    t.question(),
                                                    t.expectedConclusion(),
                                                    t.failIf(),
                                                    t.tools() == null ? List.of() : t.tools()))
                                            .toList()));
                case KnowledgeSourceAsset k ->
                    knowledge.add(new Item(
                            refOf(k), nameOf(k), k.metadata().description(), layer, "L0", true, null, List.of()));
                case SkillAsset s ->
                    skills.add(new Item(
                            refOf(s),
                            nameOf(s),
                            s.metadata().description(),
                            layer,
                            s.spec().writeLevel(),
                            true,
                            null,
                            s.spec().routing() == null || s.spec().routing().examples() == null
                                    ? List.of()
                                    : s.spec().routing().examples()));
                case ToolSourceAsset t -> {
                    if (t.spec().tools() == null) break;
                    for (var tool : t.spec().tools()) {
                        tools.add(new Tool(
                                refOf(t), tool.name(), tool.description(), tool.writeLevel(), tool.category()));
                    }
                }
                case ConnectionAsset c -> {
                    // 指向外部 Agent、但还没有登记远程 Agent 资产的连接别名，作为不可用的协作候选列出，提示先接入
                    if ("a2a".equals(c.spec().type())
                            && !remoteAliases.contains(c.spec().alias())) {
                        agents.add(new Item(
                                "connection:" + c.spec().alias(),
                                c.spec().alias().equals("SUPPLIER_RISK_AGENT")
                                        ? "供应商风险舆情 Agent"
                                        : c.spec().alias(),
                                "外部 Agent，经连接别名 " + c.spec().alias() + " 以 A2A 协议接入",
                                "external",
                                null,
                                false,
                                "还没有登记远程 Agent，先在 Studio 的“接入外部 Agent”里接入",
                                List.of("供应商风险", "舆情")));
                    }
                }
                default -> {}
            }
        }
        return new StudioCatalog(PROCESSES, agents, packs, knowledge, skills, tools, CHANNELS, PROFILES);
    }
}
