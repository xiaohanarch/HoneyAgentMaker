package dev.eab.platform.extend;

import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.EffectiveAssembly;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioService.DraftView;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.runtime.RunEvents;
import dev.eab.spec.AssetParser;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 扩展标品 Agent（主提示词 10.4 节，ADR 0011）：租户视角的标品 Agent 目录、按扩展点编辑扩展草稿、有效定义预览、
 * 私有环境测试，以及“复制为租户 Agent”。评测与发布在 ExtensionReleases。租户看到的标品资产一律经注册中心按查看者裁剪，
 * 标品指令原文不会出现在这里的任何返回值里。
 */
public final class ExtendService {

    static final String KIND = "AgentExtension";

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final StudioService studio;

    public ExtendService(PlatformServices services, RuntimeService runtime, StudioService studio) {
        this.services = services;
        this.runtime = runtime;
        this.studio = studio;
    }

    StudioService studio() {
        return studio;
    }

    PlatformServices services() {
        return services;
    }

    // ── 标品 Agent 目录 ──

    /** 租户可见的标品 Agent 摘要：描述、版本、扩展点、工具清单、公开评测集，以及本租户已有的扩展 */
    public List<Map<String, Object>> vendorAgents(String tenantId) {
        Viewer viewer = Viewer.tenant(tenantId);
        List<Map<String, Object>> result = new ArrayList<>();
        var agents = services.registry()
                .latestPublished(new AssetFilter("Agent", null, "vendor", null, null), viewer)
                .assets();
        for (Asset agent : agents) {
            var m = agent.metadata();
            var view = services.registry().view(viewer, m.namespace(), m.name(), m.version());
            if (!view.ok()) continue;
            result.add(summary(view.value().asset(), extensionsOf(tenantId, m.namespace() + "/" + m.name())));
        }
        return result;
    }

    static Map<String, Object> summary(ObjectNode asset, List<Map<String, Object>> extensions) {
        JsonNode metadata = asset.path("metadata");
        JsonNode spec = asset.path("spec");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put(
                "ref",
                metadata.path("namespace").asString() + "/"
                        + metadata.path("name").asString());
        s.put(
                "name",
                metadata.path("displayName").asString(metadata.path("name").asString()));
        s.put("description", metadata.path("description").asString(""));
        s.put("version", metadata.path("version").asString());
        s.put("writeLevel", spec.path("writePolicy").path("maxLevel").asString(""));
        s.put("extensionPoints", dev.eab.spec.Json.MAPPER.convertValue(spec.path("extensionPoints"), Object.class));
        s.put("tools", dev.eab.spec.Json.MAPPER.convertValue(spec.path("tools"), Object.class));
        s.put("evals", dev.eab.spec.Json.MAPPER.convertValue(spec.path("evals").path("suites"), Object.class));
        s.put("extensions", extensions);
        return s;
    }

    /** 本租户针对某个标品 Agent 的扩展：已发布的版本和工作区里的草稿 */
    List<Map<String, Object>> extensionsOf(String tenantId, String targetKey) {
        List<Map<String, Object>> list = new ArrayList<>();
        var published = services.registry()
                .latestPublished(new AssetFilter(KIND, null, null, tenantId, null), Viewer.tenant(tenantId))
                .assets();
        for (Asset asset : published) {
            if (asset instanceof AgentExtensionAsset ext
                    && AssetRef.parse(ext.spec().target()).key().equals(targetKey)) {
                list.add(Map.of(
                        "ref",
                        ext.metadata().namespace() + "/" + ext.metadata().name(),
                        "version",
                        ext.metadata().version(),
                        "status",
                        "published"));
            }
        }
        for (Draft draft : studio.drafts().listByTenant(tenantId, 100)) {
            if (!draft.kind().equals(KIND)) continue;
            try {
                String target = ExtensionComposer.read(draft.yaml())
                        .path("spec")
                        .path("target")
                        .asString("");
                if (!target.isEmpty() && AssetRef.parse(target).key().equals(targetKey)) {
                    list.add(Map.of("ref", draft.ref(), "draftId", draft.id(), "status", "draft"));
                }
            } catch (RuntimeException e) {
                // YAML 语法错误的草稿不列出
            }
        }
        return list;
    }

    /** 单个标品 Agent：裁剪后的资产、本租户视角的有效定义（叠加已发布的扩展）、扩展列表 */
    public Map<String, Object> vendorAgent(String tenantId, String namespace, String name) {
        Viewer viewer = Viewer.tenant(tenantId);
        var view = services.registry().view(viewer, namespace, name, null);
        if (!view.ok()
                || !"vendor".equals(view.value().summary().layer())
                || !"Agent".equals(view.value().summary().kind())) {
            throw new StudioException(404, "NOT_FOUND", "标品 Agent " + namespace + "/" + name + " 不存在");
        }
        var effective = EffectiveAssembly.assemble(
                services.registry(), viewer, EffectiveAssembly.Request.of(namespace + "/" + name, tenantId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("asset", view.value().asset());
        result.put("summary", summary(view.value().asset(), extensionsOf(tenantId, namespace + "/" + name)));
        if (effective instanceof EffectiveAssembly.Outcome.Ok ok) result.put("effective", ok.view());
        result.put("issues", effective.issues());
        return result;
    }

    // ── 扩展草稿 ──

    /** 新建扩展草稿；本租户已有该标品的已发布扩展时，从它开始编辑 */
    public DraftView createExtension(DemoUser user, String targetRef) {
        AssetRef ref = AssetRef.parse(targetRef);
        var view = services.registry().view(Viewer.tenant(user.tenantId()), ref.namespace(), ref.name(), null);
        if (!view.ok()
                || !"vendor".equals(view.value().summary().layer())
                || !"Agent".equals(view.value().summary().kind())) {
            throw new StudioException(404, "NOT_FOUND", "标品 Agent " + ref.key() + " 不存在");
        }
        String version = view.value().summary().version();
        String target = ref.key() + "@^" + version.substring(0, version.indexOf('.')) + ".0";
        String displayName =
                view.value().asset().path("metadata").path("displayName").asString(ref.name());
        String name = ref.name() + "-ext";
        String yaml = ExtensionComposer.write(
                ExtensionComposer.skeleton(user.tenantId(), user.id(), name, target, displayName));
        var published = services
                .registry()
                .latestPublished(
                        new AssetFilter(KIND, null, null, user.tenantId(), null), Viewer.tenant(user.tenantId()))
                .assets()
                .stream()
                .filter(a -> a instanceof AgentExtensionAsset e
                        && AssetRef.parse(e.spec().target()).key().equals(ref.key()))
                .findFirst();
        String namespace = ExtensionComposer.namespaceFor(user.tenantId());
        if (published.isPresent()) {
            var e = published.get();
            namespace = e.metadata().namespace();
            name = e.metadata().name();
            var existing = services.registry()
                    .view(
                            Viewer.tenant(user.tenantId()),
                            namespace,
                            name,
                            e.metadata().version());
            yaml = ExtensionComposer.write(existing.value().asset());
        }
        Draft draft =
                studio.drafts().create(user.tenantId(), user.id(), KIND, namespace, name, "扩展 " + displayName, yaml);
        if (published.isPresent())
            studio.drafts().markPublished(draft, published.get().metadata().version());
        return studio.view(studio.drafts().get(user.tenantId(), draft.id()).orElseThrow());
    }

    Draft extensionDraft(DemoUser user, String id) {
        Draft draft = studio.draft(user, id);
        if (!draft.kind().equals(KIND)) throw new StudioException(409, "NOT_EXTENSION", "草稿 " + id + " 不是扩展");
        return draft;
    }

    public DraftView applySection(DemoUser user, String id, String section, Map<String, Object> value) {
        Draft draft = extensionDraft(user, id);
        if (!ExtensionComposer.SECTIONS.containsKey(section)) {
            throw new StudioException(
                    400, "BAD_REQUEST", "扩展页只能是 " + String.join("、", ExtensionComposer.SECTIONS.keySet()));
        }
        ObjectNode tree;
        try {
            tree = ExtensionComposer.apply(ExtensionComposer.read(draft.yaml()), section, value);
        } catch (IllegalArgumentException e) {
            throw new StudioException(400, "BAD_REQUEST", e.getMessage());
        } catch (RuntimeException e) {
            throw new StudioException(422, "DRAFT_INVALID", "草稿的 YAML 无法解析，请先在 YAML 编辑模式里修正");
        }
        return studio.view(studio.drafts()
                .update(
                        draft,
                        draft.namespace(),
                        draft.name(),
                        draft.step(),
                        ExtensionComposer.write(tree),
                        draft.evalYaml()));
    }

    static Asset parse(String yaml, String what) {
        var parsed = AssetParser.parseYaml(yaml);
        if (!parsed.ok()) {
            throw new StudioException(422, "DRAFT_INVALID", what + "不合法，请先修正 YAML 里的问题", parsed.issues());
        }
        return parsed.asset();
    }

    static AgentExtensionAsset parseExtension(Draft draft) {
        if (!(parse(draft.yaml(), "扩展草稿") instanceof AgentExtensionAsset ext)) {
            throw new StudioException(422, "DRAFT_INVALID", "草稿的 kind 应为 AgentExtension");
        }
        return ext;
    }

    /** 有效定义预览：叠加扩展草稿后组装，按租户视角裁剪；标品指令是占位块，每段带来源 */
    public Map<String, Object> effective(DemoUser user, String id) {
        AgentExtensionAsset ext = parseExtension(extensionDraft(user, id));
        var outcome = EffectiveAssembly.assemble(
                services.registry(),
                Viewer.tenant(user.tenantId()),
                EffectiveAssembly.Request.withOverlay(ext.spec().target(), user.tenantId(), List.of(ext)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", outcome.ok());
        if (outcome instanceof EffectiveAssembly.Outcome.Ok ok) result.put("view", ok.view());
        result.put("issues", outcome.issues());
        result.put(
                "extension",
                ext.metadata().namespace() + "/" + ext.metadata().name() + "@"
                        + ext.metadata().version());
        return result;
    }

    // ── 私有环境测试 ──

    DemoUser testUser(DemoUser developer, String asUser) {
        String userId = asUser == null || asUser.isBlank() ? developer.id() : asUser;
        return DemoDirectory.findUser(userId)
                .filter(u -> u.tenantId().equals(developer.tenantId()))
                .orElseThrow(() -> new StudioException(400, "BAD_REQUEST", "测试用户 " + userId + " 不在本租户"));
    }

    String userToken(DemoUser user) {
        return services.tokens().issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles()));
    }

    /** 以测试用户的身份运行目标标品 Agent，扩展草稿叠加生效 */
    public RuntimeService.RunOutcome test(
            DemoUser developer, String id, String message, String asUser, String sessionId, RunEvents.Sink sink) {
        Draft draft = extensionDraft(developer, id);
        AgentExtensionAsset ext = parseExtension(draft);
        DemoUser user = testUser(developer, asUser);
        services.audit()
                .append(NewAuditEvent.of(
                                developer.id(),
                                "studio.test-run",
                                ext.versionedRef(),
                                "allow",
                                developer.id() + " 在私有环境以 " + user.id() + " 的身份测试扩展")
                        .tenantId(developer.tenantId()));
        return runtime.start(
                new RuntimeService.StartRequest(
                        user,
                        userToken(user),
                        AssetRef.parse(ext.spec().target()).key(),
                        message,
                        sessionId,
                        null,
                        null,
                        new RuntimeService.TestRun(developer.id(), List.of(draft.yaml()))),
                sink);
    }

    RuntimeService runtime() {
        return runtime;
    }

    // ── 复制为租户 Agent ──

    public record CopyResult(DraftView draft, String notice) {}

    /**
     * 复制为租户 Agent（ADR 0004）：只复制租户可见的部分，指令用标品的公开版指令；副本归租户所有，不再随标品升级。
     * 副本是一份 Agent 草稿，接着在新建 Agent 的向导里编辑、测试、发布。
     */
    public CopyResult copyAsTenant(DemoUser user, String namespace, String name) {
        Viewer viewer = Viewer.tenant(user.tenantId());
        var view = services.registry().view(viewer, namespace, name, null);
        if (!view.ok()
                || !"vendor".equals(view.value().summary().layer())
                || !"Agent".equals(view.value().summary().kind())) {
            throw new StudioException(404, "NOT_FOUND", "标品 Agent " + namespace + "/" + name + " 不存在");
        }
        ObjectNode source = view.value().asset();
        String version = view.value().summary().version();
        ObjectNode copy = TenantCopy.build(source, user, namespace + "/" + name + "@" + version);
        String yaml = ExtensionComposer.write(copy);
        Draft draft = studio.drafts()
                .create(
                        user.tenantId(),
                        user.id(),
                        "Agent",
                        copy.path("metadata").path("namespace").asString(),
                        copy.path("metadata").path("name").asString(),
                        "复制自 " + namespace + "/" + name + "@" + version,
                        yaml);
        services.audit()
                .append(NewAuditEvent.of(
                                user.id(),
                                "studio.copy",
                                namespace + "/" + name + "@" + version,
                                "allow",
                                "复制为租户 Agent 草稿 " + draft.ref())
                        .tenantId(user.tenantId()));
        return new CopyResult(studio.view(draft), TenantCopy.NOTICE);
    }
}
