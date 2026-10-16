package dev.eab.assembler;

import dev.eab.assembler.EffectiveDefinition.Segment;
import dev.eab.assembler.EffectiveDefinition.SegmentKind;
import dev.eab.spec.AssetRef;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.AssetResolver.Status;
import dev.eab.spec.Layers.Layer;
import dev.eab.spec.kinds.CommonSchemas;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Asset.PlatformRuleSetAsset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import dev.eab.spec.model.SemanticPackSpec.SemanticRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 语义包解析与指令拼接（主提示词 5.4 节第 1 条） */
final class Segments {

    private Segments() {}

    private static final int MAX_EXTENDS_DEPTH = 5;

    record PackResolution(List<SemanticPackAsset> packs, List<AssemblyIssue> issues) {}

    /**
     * 解析 Agent 与扩展挂载的语义包，连同 extends 链上的父包，按出现顺序去重。
     * 扩展的 knowledge.add 里只有 SemanticPack 会进这里，KnowledgeSource 作为文档工具处理。
     */
    static PackResolution resolveSemanticPacks(
            AgentAsset agent, List<AgentExtensionAsset> extensions, AssetResolver resolver, List<Status> statuses) {
        return resolveSemanticPacks(agent, extensions, resolver, statuses, List.of());
    }

    /**
     * packExtensions：租户层的语义包扩展。挂载的语义包被某个扩展的 spec.extends 指向时，把扩展接在后面，
     * 它的 agent_instructions 和规则随之生效，来源是租户的语义包。
     */
    static PackResolution resolveSemanticPacks(
            AgentAsset agent,
            List<AgentExtensionAsset> extensions,
            AssetResolver resolver,
            List<Status> statuses,
            List<SemanticPackAsset> packExtensions) {
        List<SemanticPackAsset> packs = new ArrayList<>();
        List<AssemblyIssue> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        class Visitor {
            void visit(String ref, Asset from, int depth, boolean optional) {
                var pack = AssetRef.isValid(ref)
                        ? resolver.resolveOfKind(ref, SemanticPackAsset.class, statuses)
                        : java.util.Optional.<SemanticPackAsset>empty();
                if (pack.isEmpty()) {
                    if (!optional) {
                        issues.add(AssemblyIssue.error(
                                "REFERENCE_UNRESOLVED", "语义包 " + ref + " 不存在或没有满足范围的已发布版本", from.versionedRef(), null));
                    }
                    return;
                }
                var p = pack.get();
                if (!seen.add(
                        AssetRef.key(p.metadata().namespace(), p.metadata().name()))) return;
                if (p.spec().extendsRef() != null && depth < MAX_EXTENDS_DEPTH) {
                    visit(p.spec().extendsRef(), p, depth + 1, false);
                }
                packs.add(p);
            }
        }
        Visitor visitor = new Visitor();
        for (String ref : agent.spec().semanticPacks()) visitor.visit(ref, agent, 0, false);
        for (var ext : extensions) {
            if (ext.spec().knowledge() == null) continue;
            for (String ref : ext.spec().knowledge().add()) visitor.visit(ref, ext, 0, true);
        }
        for (var ext : packExtensions) {
            if (ext.spec().extendsRef() == null || !AssetRef.isValid(ext.spec().extendsRef())) continue;
            var base = AssetRef.parse(ext.spec().extendsRef());
            boolean mounted = packs.stream()
                    .anyMatch(p -> p.metadata().namespace().equals(base.namespace())
                            && p.metadata().name().equals(base.name()));
            if (mounted && seen.add(AssetRef.key(ext.metadata().namespace(), ext.metadata().name()))) packs.add(ext);
        }
        return new PackResolution(packs, issues);
    }

    record SegmentResult(List<Segment> segments, List<AssemblyIssue> issues) {}

    /**
     * 按固定顺序拼接指令：
     * 平台安全规则 → 标品指令 → 标品约束 → 语义包 agent_instructions → 租户追加指令 → 租户追加约束。
     */
    static SegmentResult build(
            List<PlatformRuleSetAsset> platformRules,
            AgentAsset agent,
            List<SemanticPackAsset> packs,
            List<AgentExtensionAsset> extensions) {
        Map<String, SemanticRule> rules = new LinkedHashMap<>();
        for (var pack : packs) for (var rule : pack.spec().rules()) rules.put(rule.id(), rule);
        List<AssemblyIssue> issues = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        String agentRef = agent.versionedRef();
        // 标品层的指令对租户保密；租户自己的 Agent（例如“复制为租户 Agent”的副本）对本租户可见
        boolean agentConfidential = agent.layer() != Layer.tenant;

        for (var set : platformRules) {
            var list = set.spec().rules();
            for (int i = 0; i < list.size(); i++) {
                var rule = list.get(i);
                segments.add(new Segment(
                        "platform-rule:" + rule.id(),
                        SegmentKind.platform_rule,
                        Layer.platform,
                        set.versionedRef(),
                        "spec.rules[" + i + "].instruction",
                        rule.instruction(),
                        rule.name(),
                        true));
            }
        }
        segments.add(new Segment(
                "vendor-instructions",
                SegmentKind.vendor_instructions,
                agent.layer(),
                agentRef,
                "spec.instructions",
                agent.spec().instructions(),
                null,
                agentConfidential));
        if (agent.spec().additionalContext() != null) {
            segments.add(new Segment(
                    "vendor-context",
                    SegmentKind.vendor_context,
                    agent.layer(),
                    agentRef,
                    "spec.additionalContext",
                    agent.spec().additionalContext(),
                    null,
                    agentConfidential));
        }
        var constraints = agent.spec().constraints();
        for (int i = 0; i < constraints.size(); i++) {
            constraint(
                            constraints.get(i),
                            agent,
                            "spec.constraints[" + i + "]",
                            SegmentKind.vendor_constraint,
                            agentConfidential,
                            rules,
                            issues)
                    .ifPresent(segments::add);
        }
        for (var pack : packs) {
            var instructions = pack.spec().agentInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                segments.add(new Segment(
                        "semantic-instruction:" + pack.metadata().namespace() + "/"
                                + pack.metadata().name() + ":" + i,
                        SegmentKind.semantic_instruction,
                        pack.layer(),
                        pack.versionedRef(),
                        "spec.agent_instructions[" + i + "]",
                        instructions.get(i),
                        null,
                        false));
            }
        }
        for (var ext : extensions) {
            if (ext.spec().instructions() == null) continue;
            segments.add(new Segment(
                    "tenant-instructions:" + ext.versionedRef(),
                    SegmentKind.tenant_instructions,
                    ext.layer(),
                    ext.versionedRef(),
                    "spec.instructions.append",
                    ext.spec().instructions().append(),
                    null,
                    false));
        }
        for (var ext : extensions) {
            if (ext.spec().constraints() == null) continue;
            var appended = ext.spec().constraints().append();
            for (int i = 0; i < appended.size(); i++) {
                constraint(
                                appended.get(i),
                                ext,
                                "spec.constraints.append[" + i + "]",
                                SegmentKind.tenant_constraint,
                                false,
                                rules,
                                issues)
                        .ifPresent(segments::add);
            }
        }
        return new SegmentResult(segments, issues);
    }

    private static java.util.Optional<Segment> constraint(
            String text,
            Asset owner,
            String field,
            SegmentKind kind,
            boolean confidentialIfFreeText,
            Map<String, SemanticRule> rules,
            List<AssemblyIssue> issues) {
        String source = owner.versionedRef();
        if (!CommonSchemas.isRuleId(text)) {
            return java.util.Optional.of(new Segment(
                    kind.value() + ":" + source + ":" + field,
                    kind,
                    owner.layer(),
                    source,
                    field,
                    text,
                    null,
                    kind == SegmentKind.vendor_constraint && confidentialIfFreeText));
        }
        SemanticRule rule = rules.get(text);
        if (rule == null) {
            issues.add(AssemblyIssue.error("REFERENCE_UNRESOLVED", "约束引用的规则 " + text + " 在挂载的语义包里找不到", source, field));
            return java.util.Optional.empty();
        }
        if (rule.check() != null) {
            var check = Conditions.check(
                    Conditions.ruleCheckEnvironment(), rule.check().when());
            if (!check.valid()) {
                issues.add(AssemblyIssue.error(
                        "RULE_CHECK_INVALID", "规则 " + text + " 的检查条件不合法：" + check.reason(), source, field));
            }
        }
        // 规则来自语义包，对租户可见；文本取规则的处置建议
        return java.util.Optional.of(new Segment(
                kind.value() + ":" + source + ":" + text,
                kind,
                owner.layer(),
                source,
                field,
                "[" + text + "] " + rule.remedy(),
                text,
                false));
    }
}
