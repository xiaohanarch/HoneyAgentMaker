package dev.eab.platform.feedback;

import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.audit.AuditStore.NewAuditEvent;
import dev.eab.platform.feedback.FeedbackStore.Feedback;
import dev.eab.platform.runtime.RuntimeService;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.Json;
import dev.eab.spec.Versions;
import dev.eab.spec.Viewer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 经验回流（主提示词第 11 节，ADR 0013）：用户在统一入口指出结论有误 → 生成候选规则或候选已验证任务 → 审核队列 →
 * 审核通过后写入租户层的语义包扩展（spec.extends 指向 Agent 挂载的标品语义包），所有挂载该语义包的 Agent 下次运行即生效。
 * 经验沉淀为语义资产，不沉淀为某个 Agent 的提示词。
 */
public final class FeedbackService {

    public static final class FeedbackException extends RuntimeException {
        private final int status;
        private final String code;

        public FeedbackException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    /** 交给生成器的工具结果片段长度 */
    static final int SAMPLE_CHARS = 600;

    private final PlatformServices services;
    private final RuntimeService runtime;
    private final FeedbackStore store;
    private final FeedbackCandidates candidates;

    public FeedbackService(
            PlatformServices services, RuntimeService runtime, FeedbackStore store, FeedbackCandidates candidates) {
        this.services = services;
        this.runtime = runtime;
        this.store = store;
        this.candidates = candidates;
    }

    public FeedbackStore store() {
        return store;
    }

    /** 只能对自己的、已完成的运行提反馈 */
    public Feedback submit(DemoUser user, String runId, String reason) {
        var run = runtime.runs()
                .get(runId)
                .filter(r -> r.userId().equals(user.id()) && r.tenantId().equals(user.tenantId()))
                .orElseThrow(() -> new FeedbackException(404, "NOT_FOUND", "运行 " + runId + " 不存在"));
        if (!run.status().equals("completed")) {
            throw new FeedbackException(409, "RUN_NOT_COMPLETED", "运行还没有完成，不能反馈结论");
        }
        String agent = run.agent().replaceAll("@.*$", "");
        String answer = "";
        if (run.result() != null) {
            Map<?, ?> result = Json.MAPPER.readValue(run.result(), Map.class);
            answer = String.valueOf(result.get("text") == null ? "" : result.get("text"));
        }
        List<Map<String, String>> tools = new ArrayList<>();
        for (var span : runtime.traces().list(run.traceId())) {
            if (span.attributes().get("eab.tool.name") instanceof String name) {
                String sample = String.valueOf(span.attributes().getOrDefault("eab.tool.result", ""));
                tools.add(Map.of(
                        "name",
                        name,
                        "sample",
                        sample.length() > SAMPLE_CHARS ? sample.substring(0, SAMPLE_CHARS) : sample));
            }
        }
        var context = new FeedbackCandidates.Context(agent, run.utterance(), answer, reason, tools);
        var generated = candidates.generate(context, user.id());
        Feedback saved = store.insert(
                user.tenantId(),
                user.id(),
                runId,
                run.traceId(),
                agent,
                run.utterance(),
                reason,
                Json.MAPPER.writeValueAsString(generated.candidate()),
                generated.decidedBy());
        services.audit()
                .append(NewAuditEvent.of(user.id(), "feedback.submit", runId, "allow", "结论有误：" + reason)
                        .tenantId(user.tenantId()));
        return saved;
    }

    public Feedback reject(DemoUser reviewer, String id, String note) {
        Feedback f = pending(reviewer, id);
        store.review(reviewer.tenantId(), id, "rejected", reviewer.id(), note, null, f.candidate());
        audit(reviewer, f, "reject", "驳回候选规则");
        return store.get(reviewer.tenantId(), id).orElseThrow();
    }

    /**
     * 审核通过：把候选写进租户的语义包扩展（新版本并发布）。edited 是审核人改过的候选，没有改时为 null。
     */
    public Feedback approve(DemoUser reviewer, String id, Map<String, Object> edited, String note) {
        Feedback f = pending(reviewer, id);
        Map<String, Object> candidate = edited != null ? edited : Json.MAPPER.readValue(f.candidate(), Map.class);
        String basePack = basePack(reviewer.tenantId(), f.agent());
        String packRef = TenantPacks.append(services, reviewer.tenantId(), basePack, candidate, f.reason());
        store.review(
                reviewer.tenantId(),
                id,
                "approved",
                reviewer.id(),
                note,
                packRef,
                Json.MAPPER.writeValueAsString(candidate));
        audit(reviewer, f, "approve", "写入 " + packRef);
        return store.get(reviewer.tenantId(), id).orElseThrow();
    }

    private Feedback pending(DemoUser reviewer, String id) {
        Feedback f = store.get(reviewer.tenantId(), id)
                .orElseThrow(() -> new FeedbackException(404, "NOT_FOUND", "反馈 " + id + " 不存在"));
        if (!f.status().equals("pending")) throw new FeedbackException(409, "ALREADY_REVIEWED", "反馈 " + id + " 已经审核过");
        return f;
    }

    private void audit(DemoUser reviewer, Feedback f, String action, String reason) {
        services.audit()
                .append(NewAuditEvent.of(reviewer.id(), "feedback." + action, f.id(), "allow", reason)
                        .tenantId(reviewer.tenantId()));
    }

    /** Agent 挂载的第一个标品语义包，候选规则扩展它 */
    String basePack(String tenantId, String agent) {
        var definition = runtime.assemble(agent, tenantId);
        return definition.provenance().semanticPacks().stream()
                .filter(ref -> ref.startsWith("vendor."))
                .map(ref -> ref.replaceAll("@.*$", ""))
                .findFirst()
                .orElseThrow(() -> new FeedbackException(409, "NO_SEMANTIC_PACK", agent + " 没有挂载标品语义包，经验无处沉淀"));
    }

    /** 租户层语义包扩展的读写 */
    static final class TenantPacks {
        private TenantPacks() {}

        static final Pattern RULE = Pattern.compile("^([A-Z][A-Z0-9]*)-R-(\\d{3})$");
        static final Pattern TASK = Pattern.compile("^([A-Z][A-Z0-9]*)-VT-(\\d{3})$");

        static String namespace(String tenantId) {
            return "tenant." + tenantId + ".semantic";
        }

        static String name(String basePack) {
            return basePack.substring(basePack.indexOf('/') + 1) + "-ext";
        }

        /** 追加一条规则或已验证任务，写成新版本并发布，返回 namespace/name@version */
        static String append(
                PlatformServices services,
                String tenantId,
                String basePack,
                Map<String, Object> candidate,
                String reason) {
            Viewer viewer = Viewer.tenant(tenantId);
            String ns = namespace(tenantId);
            String name = name(basePack);
            var resolver = services.registry().resolverFor(viewer);
            var versions = resolver.listVersions(ns, name);
            ObjectNode tree;
            String version;
            if (versions.isEmpty()) {
                tree = skeleton(ns, name, basePack, tenantId);
                version = "1.0.0";
            } else {
                String latest = versions.getFirst().version();
                tree = (ObjectNode) Json.toTree(resolver.get(ns, name, latest).orElseThrow());
                String[] p = latest.split("\\.");
                version = p[0] + "." + (Integer.parseInt(p[1]) + 1) + ".0";
            }
            ((ObjectNode) tree.path("metadata")).put("version", version);
            ObjectNode spec = (ObjectNode) tree.path("spec");
            String prefix =
                    "T" + tenantId.replaceAll("[^0-9A-Za-z]", "").toUpperCase().replaceFirst("^T", "");
            if ("verified_task".equals(candidate.get("kind"))) {
                ArrayNode tasks = array(spec, "verified_tasks");
                ObjectNode t = tasks.addObject();
                t.put("id", prefix + "-VT-" + next(tasks, TASK));
                t.put("question", String.valueOf(candidate.get("question")));
                t.put("expected_conclusion", String.valueOf(candidate.get("expectedConclusion")));
                if (candidate.get("failIf") != null) t.put("fail_if", String.valueOf(candidate.get("failIf")));
            } else {
                ArrayNode rules = array(spec, "rules");
                String ruleId = prefix + "-R-" + next(rules, RULE);
                ObjectNode r = rules.addObject();
                r.put("id", ruleId);
                r.put("severity", String.valueOf(candidate.getOrDefault("severity", "warn")));
                r.put("description", String.valueOf(candidate.get("description")));
                r.put("remedy", String.valueOf(candidate.get("remedy")));
                if (candidate.get("checkTool") != null && candidate.get("checkWhen") != null) {
                    ObjectNode check = r.putObject("check");
                    check.put("tool", String.valueOf(candidate.get("checkTool")));
                    check.put("when", String.valueOf(candidate.get("checkWhen")));
                }
                // 规则的处置建议同时作为语义包的 agent_instructions，挂载的 Agent 在提示词里也能看到（片段来源是租户语义包）
                array(spec, "agent_instructions").add("[" + ruleId + "] " + candidate.get("remedy"));
            }
            String yaml = Json.YAML.writeValueAsString(tree);
            var put = services.registry().put(viewer, yaml, "经验回流：" + reason);
            if (!put.ok()) {
                throw new FeedbackException(
                        422,
                        "PACK_INVALID",
                        "写入租户语义包扩展失败：" + put.error().message() + " "
                                + put.error().issues());
            }
            var published = services.registry().setStatus(viewer, ns, name, version, AssetResolver.Status.published);
            if (!published.ok()) {
                throw new FeedbackException(
                        422,
                        "PACK_PUBLISH_FAILED",
                        "发布租户语义包扩展失败：" + published.error().message());
            }
            return ns + "/" + name + "@" + version;
        }

        static ObjectNode skeleton(String ns, String name, String basePack, String tenantId) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("apiVersion", "eab.dev/v1");
            asset.put("kind", "SemanticPack");
            asset.put(
                    "metadata",
                    Map.of(
                            "name",
                            name,
                            "namespace",
                            ns,
                            "version",
                            "1.0.0",
                            "displayName",
                            "租户经验 · " + basePack,
                            "description",
                            "经验回流审核通过的规则与已验证任务，扩展 " + basePack,
                            "owner",
                            "tenant-" + tenantId));
            asset.put("spec", Map.of("extends", basePack));
            return Json.MAPPER.valueToTree(asset);
        }

        static ArrayNode array(ObjectNode spec, String field) {
            JsonNode node = spec.get(field);
            if (node instanceof ArrayNode a) return a;
            return spec.putArray(field);
        }

        /** 已有编号里最大的序号加一，三位数字 */
        static String next(ArrayNode items, Pattern pattern) {
            int max = 0;
            for (JsonNode item : items) {
                Matcher m = pattern.matcher(item.path("id").asString(""));
                if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(2)));
            }
            return String.format("%03d", max + 1);
        }

        static int compare(String a, String b) {
            return Versions.compare(a, b);
        }
    }
}
