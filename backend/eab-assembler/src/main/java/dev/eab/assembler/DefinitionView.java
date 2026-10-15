package dev.eab.assembler;

import dev.eab.assembler.EffectiveDefinition.Segment;
import dev.eab.assembler.EffectiveDefinition.SegmentKind;
import dev.eab.spec.Json;
import dev.eab.spec.Viewer;
import dev.eab.spec.Visibility;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 有效定义按查看者裁剪后的视图，以及发给模型的系统提示词 */
public final class DefinitionView {

    private DefinitionView() {}

    /**
     * 按查看者裁剪有效定义（主提示词 5.4 节第 7 条、10.4 节）。租户视角下，标品指令和平台规则原文替换为
     * 不可展开的占位块，平台规则只保留规则名；来源标记、语义包指令、租户自己的内容照常显示。
     */
    public static ObjectNode view(EffectiveDefinition definition, Viewer viewer, UnaryOperator<String> digest) {
        ObjectNode view = (ObjectNode) Json.toTree(definition);
        if (!(viewer instanceof Viewer.Tenant)) return view;
        ArrayNode segments = view.putArray("segments");
        for (Segment segment : definition.segments()) {
            ObjectNode node = (ObjectNode) Json.toTree(segment);
            if (segment.confidential()) {
                String label = segment.kind() == SegmentKind.platform_rule
                        ? Visibility.PLATFORM_RULE_LABEL
                        : Visibility.VENDOR_INSTRUCTION_LABEL;
                node.set("text", Json.toTree(Visibility.redact(segment.text(), label, digest)));
            }
            segments.add(node);
        }
        return view;
    }

    /** 把有效定义的指令片段拼成发给模型的系统提示词，每段带来源标记 */
    public static String renderInstructions(EffectiveDefinition definition) {
        return definition.segments().stream()
                .map(s -> "<!-- " + s.kind().value() + " " + s.source() + " " + s.field() + " -->\n"
                        + s.text().strip())
                .collect(Collectors.joining("\n\n"));
    }
}
