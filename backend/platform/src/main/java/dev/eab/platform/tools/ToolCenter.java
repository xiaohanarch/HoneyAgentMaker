package dev.eab.platform.tools;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.cli.ImportBoTools;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.ConnectionAsset;
import dev.eab.spec.model.Asset.SkillAsset;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.model.StepSpecs.Step;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * 工具中心（主提示词 10.7 节，ADR 0011）：本租户可见的全部工具及其写操作级别、被哪些 Agent、扩展、技能使用；
 * 从 DemoERP 的 OpenAPI 勾选生成租户的 BO 工具来源；登记 MCP Server（拉取工具清单、提交审批）。
 */
public final class ToolCenter {

    static final List<String> WRITE_LEVELS = List.of("L0", "L1", "L2", "L3");

    private final PlatformServices services;
    private final McpApprovalStore approvals;
    private final String erpUrl;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ToolCenter(PlatformServices services, McpApprovalStore approvals, String erpUrl) {
        this.services = services;
        this.approvals = approvals;
        this.erpUrl = erpUrl;
    }

    public McpApprovalStore approvals() {
        return approvals;
    }

    static String refOf(Asset asset) {
        return asset.metadata().namespace() + "/" + asset.metadata().name();
    }

    // ── 工具清单与引用关系 ──

    public Map<String, Object> list(String tenantId) {
        var published = services.registry()
                .latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets();
        Map<String, Set<String>> usedBy = usedBy(published);
        List<Map<String, Object>> sources = new ArrayList<>();
        List<Map<String, Object>> skills = new ArrayList<>();
        for (Asset asset : published) {
            if (asset instanceof ToolSourceAsset source) {
                String ref = refOf(source);
                List<Map<String, Object>> tools = new ArrayList<>();
                for (var t : source.spec().tools()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", t.name());
                    item.put("description", t.description());
                    item.put("writeLevel", t.writeLevel() == null ? "" : t.writeLevel());
                    item.put("category", t.category() == null ? "" : t.category());
                    item.put("usedBy", List.copyOf(usedBy.getOrDefault(ref + "#" + t.name(), Set.of())));
                    tools.add(item);
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("ref", ref);
                s.put("version", source.metadata().version());
                s.put(
                        "name",
                        source.metadata().displayName() == null
                                ? source.metadata().name()
                                : source.metadata().displayName());
                s.put("layer", source.layer().name());
                s.put("type", source.spec().type());
                s.put("connection", source.spec().connection());
                if ("mcp".equals(source.spec().type())) {
                    s.put(
                            "approval",
                            approvals.find(tenantId, ref).map(a -> a.status()).orElse("unregistered"));
                }
                s.put("usedBy", List.copyOf(usedBy.getOrDefault(ref, Set.of())));
                s.put("tools", tools);
                sources.add(s);
            } else if (asset instanceof SkillAsset skill) {
                String ref = refOf(skill);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("ref", ref);
                s.put("version", skill.metadata().version());
                s.put(
                        "name",
                        skill.metadata().displayName() == null
                                ? skill.metadata().name()
                                : skill.metadata().displayName());
                s.put("layer", skill.layer().name());
                s.put(
                        "writeLevel",
                        skill.spec().writeLevel() == null ? "" : skill.spec().writeLevel());
                s.put(
                        "steps",
                        skill.spec().steps() == null ? 0 : skill.spec().steps().size());
                s.put("usedBy", List.copyOf(usedBy.getOrDefault(ref, Set.of())));
                skills.add(s);
            }
        }
        return Map.of("sources", sources, "skills", skills);
    }

    /**
     * 引用关系：键是工具来源 ref、工具来源 ref#工具名、技能 ref，值是使用它的 Agent、扩展、技能。
     * Agent 挂载工具来源时没有限定工具则算作使用全部工具。
     */
    static Map<String, Set<String>> usedBy(List<Asset> assets) {
        Map<String, List<String>> toolsOfSource = new LinkedHashMap<>();
        Map<String, String> sourceOfTool = new LinkedHashMap<>();
        for (Asset a : assets) {
            if (a instanceof ToolSourceAsset s) {
                List<String> names =
                        s.spec().tools().stream().map(t -> t.name()).toList();
                toolsOfSource.put(refOf(s), names);
                for (String n : names) sourceOfTool.putIfAbsent(n, refOf(s));
            }
        }
        Map<String, Set<String>> used = new LinkedHashMap<>();
        java.util.function.BiConsumer<String, String> add = (key, user) ->
                used.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(user);
        for (Asset a : assets) {
            String user = refOf(a);
            if (a instanceof AgentAsset agent) {
                for (var entry : agent.spec().tools()) {
                    var n = entry.normalize();
                    if (n.type().equals("api") || n.type().equals("mcp")) {
                        String source = dev.eab.spec.AssetRef.parse(n.ref()).key();
                        add.accept(source, user);
                        for (String tool :
                                n.tools() == null ? toolsOfSource.getOrDefault(source, List.of()) : n.tools()) {
                            add.accept(source + "#" + tool, user);
                        }
                    } else if (n.ref() != null) {
                        add.accept(dev.eab.spec.AssetRef.parse(n.ref()).key(), user);
                    }
                }
            } else if (a instanceof AgentExtensionAsset ext && ext.spec().tools() != null) {
                for (var entry : ext.spec().tools().add()) {
                    Object v = Json.MAPPER.convertValue(entry, Object.class);
                    if (v instanceof String ref) {
                        String key = dev.eab.spec.AssetRef.parse(ref).key();
                        add.accept(key, user);
                        for (String tool : toolsOfSource.getOrDefault(key, List.of()))
                            add.accept(key + "#" + tool, user);
                    }
                }
            } else if (a instanceof SkillAsset skill && skill.spec().steps() != null) {
                for (Step step : flatten(skill.spec().steps())) {
                    if (step.tool() != null) {
                        String source = step.source() != null ? step.source() : sourceOfTool.get(step.tool());
                        if (source != null)
                            add.accept(dev.eab.spec.AssetRef.parse(source).key() + "#" + step.tool(), user);
                    }
                    if (step.skill() != null)
                        add.accept(dev.eab.spec.AssetRef.parse(step.skill()).key(), user);
                }
            }
        }
        return used;
    }

    static List<Step> flatten(List<Step> steps) {
        List<Step> all = new ArrayList<>();
        if (steps == null) return all;
        for (Step s : steps) {
            all.add(s);
            all.addAll(flatten(s.thenSteps()));
            all.addAll(flatten(s.elseSteps()));
            if (s.branches() != null) s.branches().forEach(b -> all.addAll(flatten(b)));
        }
        return all;
    }

    // ── BO API 导入 ──

    JsonNode openApi() {
        try {
            var response = http.send(
                    HttpRequest.newBuilder(URI.create(erpUrl).resolve("/bo/v1/openapi.json"))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new StudioException(502, "ERP_ERROR", "读取 DemoERP 的 OpenAPI 失败：HTTP " + response.statusCode());
            }
            return Json.MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw new StudioException(502, "ERP_UNREACHABLE", "无法连接 DemoERP：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StudioException(503, "INTERRUPTED", "请求被中断");
        }
    }

    /** 可以导入的 BO 操作：名称、说明、方法与路径、写操作级别（OpenAPI 的 x-write-level），以及是否已经有工具来源包含它 */
    public List<Map<String, Object>> boOperations(String tenantId) {
        JsonNode doc = openApi();
        Set<String> existing = new LinkedHashSet<>();
        for (Asset a : services.registry()
                .latestPublished(new AssetFilter("ToolSource", null, null, null, null), Viewer.tenant(tenantId))
                .assets()) {
            if (a instanceof ToolSourceAsset s && "bo-api".equals(s.spec().type())) {
                s.spec().tools().forEach(t -> existing.add(t.name()));
            }
        }
        List<Map<String, Object>> ops = new ArrayList<>();
        for (var g : ImportBoTools.fromOpenApi(doc).values()) {
            JsonNode op = doc.path("paths").path(g.path()).path(g.method().toLowerCase());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", g.name());
            item.put(
                    "description",
                    op.path("description").asString(op.path("summary").asString(g.name())));
            item.put("method", g.method());
            item.put("path", g.path());
            item.put("writeLevel", g.writeLevel());
            item.put("imported", existing.contains(g.name()));
            ops.add(item);
        }
        return ops;
    }

    /** 勾选生成租户的 BO 工具来源：描述、参数、写操作级别都取自 OpenAPI，连接别名固定为 DEMO_ERP */
    public Map<String, Object> importBo(DemoUser user, String name, String displayName, List<String> operations) {
        if (operations == null || operations.isEmpty()) throw new StudioException(400, "BAD_REQUEST", "至少勾选一个操作");
        JsonNode doc = openApi();
        var generated = ImportBoTools.fromOpenApi(doc);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (String op : operations) {
            var g = generated.get(op);
            if (g == null) throw new StudioException(400, "BAD_REQUEST", "DemoERP 没有操作 " + op);
            if (!WRITE_LEVELS.contains(g.writeLevel())) {
                throw new StudioException(422, "WRITE_LEVEL_MISSING", "操作 " + op + " 没有在 OpenAPI 里声明写操作级别，不能生成工具");
            }
            JsonNode o = doc.path("paths").path(g.path()).path(g.method().toLowerCase());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", g.name());
            tool.put(
                    "description",
                    o.path("description").asString(o.path("summary").asString(g.name())));
            tool.put("writeLevel", g.writeLevel());
            tool.put("category", "L0".equals(g.writeLevel()) ? "query" : "write");
            tool.put("operation", Map.of("method", g.method(), "path", g.path()));
            tool.put("inputSchema", Json.MAPPER.convertValue(g.inputSchema(), Object.class));
            tools.add(tool);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "bo-api");
        spec.put("connection", "DEMO_ERP");
        spec.put("openapiPath", "/bo/v1/openapi.json");
        spec.put("tools", tools);
        return publishSource(user, name, displayName, "从 DemoERP 的 OpenAPI 勾选生成", spec, "工具中心导入 BO API");
    }

    // ── MCP Server 登记 ──

    /** 本租户 mcp 类型的连接别名在某个环境的地址 */
    String mcpUrl(String tenantId, String alias, String environment) {
        for (Asset a : services.registry()
                .latestPublished(new AssetFilter("Connection", null, null, tenantId, null), Viewer.tenant(tenantId))
                .assets()) {
            if (a instanceof ConnectionAsset c && c.spec().alias().equals(alias)) {
                if (!"mcp".equals(c.spec().type()))
                    throw new StudioException(400, "BAD_REQUEST", "连接 " + alias + " 不是 MCP 类型");
                var binding = c.spec().bindings().get(environment);
                if (binding == null)
                    throw new StudioException(400, "BAD_REQUEST", "连接 " + alias + " 没有绑定 " + environment + " 环境");
                return binding.url();
            }
        }
        throw new StudioException(404, "NOT_FOUND", "连接别名 " + alias + " 不存在，请先在连接中心登记");
    }

    public List<Map<String, Object>> mcpTools(String tenantId, String alias) {
        String url = mcpUrl(tenantId, alias, "dev");
        try {
            return McpToolLister.list(url).stream()
                    .map(t -> Map.<String, Object>of(
                            "name", t.name(), "description", t.description(), "inputSchema", t.inputSchema()))
                    .toList();
        } catch (RuntimeException e) {
            throw new StudioException(502, "MCP_UNREACHABLE", "无法从 " + alias + " 拉取工具清单：" + e.getMessage());
        }
    }

    public record McpToolChoice(String name, String writeLevel) {}

    /** 登记 MCP Server：按所选工具生成租户的 mcp 工具来源并发布，同时提交审批；审批通过前不能用在 prod */
    public Map<String, Object> registerMcp(
            DemoUser user, String name, String displayName, String alias, List<McpToolChoice> choices) {
        if (choices == null || choices.isEmpty()) throw new StudioException(400, "BAD_REQUEST", "至少选择一个工具");
        Map<String, Map<String, Object>> available = new LinkedHashMap<>();
        for (var t : mcpTools(user.tenantId(), alias)) available.put(String.valueOf(t.get("name")), t);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpToolChoice c : choices) {
            var t = available.get(c.name());
            if (t == null) throw new StudioException(400, "BAD_REQUEST", alias + " 没有工具 " + c.name());
            if (!WRITE_LEVELS.contains(c.writeLevel()))
                throw new StudioException(400, "BAD_REQUEST", "工具 " + c.name() + " 的写操作级别必须声明为 L0 到 L3");
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", c.name().replace('-', '_').toLowerCase());
            tool.put("description", t.get("description"));
            tool.put("writeLevel", c.writeLevel());
            tool.put("category", "L0".equals(c.writeLevel()) ? "query" : "write");
            if (t.get("inputSchema") instanceof Map<?, ?> schema && !schema.isEmpty()) tool.put("inputSchema", schema);
            tools.add(tool);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "mcp");
        spec.put("connection", alias);
        spec.put("tools", tools);
        Map<String, Object> result = new LinkedHashMap<>(
                publishSource(user, name, displayName, "登记的外部 MCP Server（" + alias + "）", spec, "工具中心登记 MCP Server"));
        var approval = approvals.request(user.tenantId(), String.valueOf(result.get("ref")), user.id());
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(), "mcp.approval.request", approval.source(), "allow", "提交 MCP Server 审批")
                        .tenantId(user.tenantId()));
        result.put("approval", approval.status());
        return result;
    }

    public McpApprovalStore.Approval decide(DemoUser admin, long id, boolean approved, String note) {
        var approval = approvals
                .get(admin.tenantId(), id)
                .orElseThrow(() -> new StudioException(404, "NOT_FOUND", "审批 " + id + " 不存在"));
        var decided = approvals.decide(approval, approved, admin.id(), note);
        services.audit()
                .append(NewAuditEvent.of(
                                admin.id(),
                                "mcp.approval.decide",
                                approval.source(),
                                approved ? "allow" : "deny",
                                approved ? "审批通过" : "审批拒绝：" + (note == null ? "" : note))
                        .tenantId(admin.tenantId()));
        return decided;
    }

    /** 写入并发布一个租户工具来源；同名时在最高版本上升一个次版本号 */
    private Map<String, Object> publishSource(
            DemoUser user, String name, String displayName, String description, Map<String, Object> spec, String note) {
        String namespace = "tenant." + user.tenantId() + ".tools";
        Viewer viewer = Viewer.tenant(user.tenantId());
        var versions = services.registry().resolverFor(viewer).listVersions(namespace, name);
        String version = "1.0.0";
        if (!versions.isEmpty()) {
            String max = versions.stream()
                    .map(AssetResolver.VersionInfo::version)
                    .max(Versions::compare)
                    .orElseThrow();
            String[] p = max.split("[.-]");
            version = p[0] + "." + (Integer.parseInt(p[1]) + 1) + ".0";
        }
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "ToolSource");
        asset.put(
                "metadata",
                Map.of(
                        "name", name,
                        "namespace", namespace,
                        "version", version,
                        "displayName", displayName == null || displayName.isBlank() ? name : displayName,
                        "description", description,
                        "owner", user.id()));
        asset.put("spec", spec);
        String yaml = Json.YAML.writeValueAsString(asset);
        var put = services.registry().put(viewer, yaml, note);
        if (!put.ok())
            throw new StudioException(
                    422,
                    "TOOL_SOURCE_INVALID",
                    "工具来源没有保存：" + put.error().message(),
                    put.error().issues());
        var published = services.registry().setStatus(viewer, namespace, name, version, AssetResolver.Status.published);
        if (!published.ok()) {
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "工具来源发布失败：" + published.error().message(),
                    published.error().issues());
        }
        return Map.of("ref", namespace + "/" + name, "version", version, "tools", ((List<?>) spec.get("tools")).size());
    }
}
