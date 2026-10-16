package dev.eab.platform.skills;

import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.identity.TokenService;
import dev.eab.platform.registry.OverlayResolver;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.platform.studio.StudioService;
import dev.eab.platform.studio.StudioService.DraftView;
import dev.eab.platform.studio.StudioService.StudioException;
import dev.eab.platform.studio.StudioStores.Draft;
import dev.eab.spec.AssetParser;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Lint;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.SkillAsset;
import dev.eab.tracing.SpanRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/**
 * 技能编辑器（主提示词 10.6 节，ADR 0011）：技能草稿（新建或从已有技能复制）、编辑步骤列表、单独测试（填入参，
 * 查看每一步的输入输出）、发布。单独测试时技能挂在一个临时的测试 Agent 上，经工具网关执行，鉴权与审计照常。
 */
public final class SkillStudio {

    static final String KIND = "Skill";
    static final String TESTER = "skill-tester";

    static final YAMLMapper YAML = YAMLMapper.builder()
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .build();

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final StudioService studio;

    public SkillStudio(PlatformServices services, RuntimeService runtime, StudioService studio) {
        this.services = services;
        this.runtime = runtime;
        this.studio = studio;
    }

    static String namespaceFor(String tenantId) {
        return "tenant." + tenantId + ".skills";
    }

    static ObjectNode read(String yaml) {
        JsonNode node = Json.YAML.readTree(yaml);
        if (!(node instanceof ObjectNode object)) throw new StudioException(422, "DRAFT_INVALID", "草稿不是一个 YAML 对象");
        return object;
    }

    /** 新建技能草稿；from 给出时复制已有技能（标品技能复制成租户技能，不随标品升级） */
    public DraftView create(DemoUser user, String name, String displayName, String from) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", name);
        metadata.put("namespace", namespaceFor(user.tenantId()));
        metadata.put("version", "1.0.0");
        metadata.put("displayName", displayName == null || displayName.isBlank() ? name : displayName);
        metadata.put("owner", user.id());
        Object spec;
        if (from != null && !from.isBlank()) {
            SkillAsset source = services.registry()
                    .resolverFor(Viewer.tenant(user.tenantId()))
                    .resolveOfKind(from, SkillAsset.class, AssetResolver.PUBLISHED)
                    .orElseThrow(() -> new StudioException(404, "NOT_FOUND", "技能 " + from + " 不存在"));
            metadata.put(
                    "description",
                    source.metadata().description() == null
                            ? "复制自 " + from
                            : source.metadata().description());
            metadata.put("labels", Map.of("copiedFrom", source.versionedRef()));
            spec = Json.toTree(source.spec());
        } else {
            metadata.put("description", "租户技能");
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("writeLevel", "L0");
            s.put("inputSchema", Map.of("type", "object", "properties", Map.of()));
            s.put("outputSchema", Map.of("type", "object"));
            s.put("steps", List.of(Map.of("id", "step-1", "type", "transform", "name", "返回入参", "expression", "input")));
            spec = s;
        }
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", KIND);
        asset.put("metadata", metadata);
        asset.put("spec", spec);
        String yaml = YAML.writeValueAsString(asset);
        Draft draft = studio.drafts()
                .create(
                        user.tenantId(),
                        user.id(),
                        KIND,
                        namespaceFor(user.tenantId()),
                        name,
                        "技能 " + metadata.get("displayName"),
                        yaml);
        return studio.view(draft);
    }

    Draft skillDraft(DemoUser user, String id) {
        Draft draft = studio.draft(user, id);
        if (!draft.kind().equals(KIND)) throw new StudioException(409, "NOT_SKILL", "草稿 " + id + " 不是技能");
        return draft;
    }

    /** 替换步骤列表（编辑器的步骤表单），其余字段在 YAML 编辑模式里改 */
    public DraftView putSteps(DemoUser user, String id, List<Map<String, Object>> steps) {
        Draft draft = skillDraft(user, id);
        ObjectNode tree = read(draft.yaml());
        ObjectNode spec = tree.get("spec") instanceof ObjectNode s ? s : tree.putObject("spec");
        spec.set("steps", Json.MAPPER.valueToTree(steps == null ? List.of() : steps));
        return studio.view(studio.drafts()
                .update(
                        draft,
                        draft.namespace(),
                        draft.name(),
                        draft.step(),
                        YAML.writeValueAsString(tree),
                        draft.evalYaml()));
    }

    static SkillAsset parse(Draft draft) {
        var parsed = AssetParser.parseYaml(draft.yaml());
        if (!parsed.ok() || !(parsed.asset() instanceof SkillAsset skill)) {
            throw new StudioException(422, "DRAFT_INVALID", "技能草稿不合法，请先修正 YAML 里的问题", parsed.issues());
        }
        return skill;
    }

    /** 挂着被测技能的临时测试 Agent，只存在于这次测试的叠加里 */
    static String testerYaml(String tenantId, SkillAsset skill) {
        String level = skill.spec().writeLevel() == null ? "L0" : skill.spec().writeLevel();
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("apiVersion", "eab.dev/v1");
        asset.put("kind", "Agent");
        asset.put(
                "metadata",
                Map.of(
                        "name",
                        TESTER,
                        "namespace",
                        "tenant." + tenantId + ".agents",
                        "version",
                        "0.0.1",
                        "displayName",
                        "技能单独测试"));
        asset.put(
                "spec",
                Map.of(
                        "expertise", "单独测试一个技能",
                        "instructions", "单独测试一个技能",
                        "model", Map.of("profile", "fast"),
                        "tools",
                                List.of(Map.of(
                                        "skill",
                                        skill.metadata().namespace() + "/"
                                                + skill.metadata().name())),
                        "writePolicy", Map.of("maxLevel", level),
                        "routing", Map.of("description", "单独测试技能", "examples", List.of(), "direct", false)));
        return YAML.writeValueAsString(asset);
    }

    public record StepResult(
            String id,
            String name,
            String type,
            String status,
            String input,
            String output,
            String error,
            long durationMs) {}

    public record SkillTestView(
            String status,
            Object output,
            String error,
            String pending,
            List<StepResult> steps,
            String runId,
            String traceId) {}

    /** 单独测试：以测试用户的身份执行技能，返回结果和每一步的输入输出 */
    public SkillTestView test(DemoUser developer, String id, Map<String, Object> input, String asUser) {
        Draft draft = skillDraft(developer, id);
        SkillAsset skill = parse(draft);
        String userId = asUser == null || asUser.isBlank() ? "zhangmin" : asUser;
        DemoUser user = DemoDirectory.findUser(userId)
                .filter(u -> u.tenantId().equals(developer.tenantId()))
                .orElseThrow(() -> new StudioException(400, "BAD_REQUEST", "测试用户 " + userId + " 不在本租户"));
        String testerYaml = testerYaml(developer.tenantId(), skill);
        Asset tester = AssetParser.parseYaml(testerYaml).asset();
        services.audit()
                .append(NewAuditEvent.of(
                                developer.id(),
                                "studio.skill-test",
                                skill.versionedRef(),
                                "allow",
                                developer.id() + " 以 " + user.id() + " 的身份单独测试技能")
                        .tenantId(developer.tenantId()));
        var result = runtime.testTool(
                user,
                services.tokens().issueUser(new TokenService.UserClaims(user.id(), user.tenantId(), user.roles())),
                "tenant." + developer.tenantId() + ".agents/" + TESTER,
                List.of(skill, tester),
                List.of(draft.yaml(), testerYaml),
                skill.metadata().name(),
                input == null ? Map.of() : input,
                developer.id());
        List<StepResult> steps = new ArrayList<>();
        for (SpanRecord span : runtime.traces().list(result.traceId())) {
            Object stepId = span.attributes().get("eab.step.id");
            if (span.kind() != SpanRecord.SpanKind.workflow || stepId == null) continue;
            long duration = span.endTime() == null
                    ? 0
                    : java.time.Duration.between(
                                    java.time.Instant.parse(span.startTime()), java.time.Instant.parse(span.endTime()))
                            .toMillis();
            steps.add(new StepResult(
                    String.valueOf(stepId),
                    span.name().replaceFirst("^步骤 · ", ""),
                    String.valueOf(span.attributes().getOrDefault("eab.step.type", "")),
                    span.status().name(),
                    (String) span.attributes().get("eab.step.input"),
                    (String) span.attributes().get("eab.step.output"),
                    span.statusMessage(),
                    duration));
        }
        var outcome = result.outcome();
        String status = result.pending() != null ? "confirmation" : outcome.ok() ? "ok" : "failed";
        String error = outcome.ok() ? null : String.valueOf(outcome.error().get("reason"));
        return new SkillTestView(
                status, outcome.data(), error, result.pending(), steps, result.runId(), result.traceId());
    }

    public record PublishResult(String ref, String version, boolean unchanged) {}

    /** 发布：静态检查无错误，写入注册中心并签名；内容没变时不升版本 */
    public PublishResult publish(DemoUser user, String id) {
        Draft draft = skillDraft(user, id);
        SkillAsset skill = parse(draft);
        Viewer viewer = Viewer.tenant(user.tenantId());
        var resolver = new OverlayResolver(services.registry().resolverFor(viewer), List.of(skill));
        var errors = Lint.lint(skill, resolver, AssetResolver.LIVE).stream()
                .filter(i -> i.severity().equals("error"))
                .toList();
        if (!errors.isEmpty()) throw new StudioException(409, "LINT_FAILED", "静态检查有错误，不能发布", errors);
        String ns = skill.metadata().namespace();
        String name = skill.metadata().name();
        var base = services.registry().resolverFor(viewer);
        var versions = base.listVersions(ns, name);
        String hash = StudioService.contentHash(skill);
        if (!versions.isEmpty()) {
            var latest = versions.getFirst();
            var existing = base.get(ns, name, latest.version());
            if (existing.isPresent()
                    && latest.status() == AssetResolver.Status.published
                    && StudioService.contentHash(existing.get()).equals(hash)) {
                return new PublishResult(ns + "/" + name, latest.version(), true);
            }
        }
        String version = skill.metadata().version();
        if (!versions.isEmpty()) {
            String max = versions.stream()
                    .map(AssetResolver.VersionInfo::version)
                    .max(Versions::compare)
                    .orElseThrow();
            if (Versions.compare(version, max) <= 0) {
                String[] p = max.split("[.-]");
                version = p[0] + "." + p[1] + "." + (Integer.parseInt(p[2]) + 1);
            }
        }
        ObjectNode tree = read(draft.yaml());
        ((ObjectNode) tree.path("metadata")).put("version", version);
        String yaml = YAML.writeValueAsString(tree);
        var put = services.registry().put(viewer, yaml, "技能编辑器发布");
        if (!put.ok())
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "写入注册中心失败：" + put.error().message(),
                    put.error().issues());
        var published = services.registry().setStatus(viewer, ns, name, version, AssetResolver.Status.published);
        if (!published.ok()) {
            throw new StudioException(
                    422,
                    "PUBLISH_FAILED",
                    "发布失败：" + published.error().message(),
                    published.error().issues());
        }
        studio.drafts().update(draft, draft.namespace(), draft.name(), draft.step(), yaml, draft.evalYaml());
        studio.drafts().markPublished(draft, version);
        return new PublishResult(ns + "/" + name, version, false);
    }
}
