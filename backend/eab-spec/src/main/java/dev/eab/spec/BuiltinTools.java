package dev.eab.spec;

import java.util.List;
import java.util.Optional;

/** 平台内置工具与语义查询工具 */
public final class BuiltinTools {

    private BuiltinTools() {}

    public record BuiltinTool(String name, String description, String writeLevel) {}

    /** 平台内置工具，Agent 用 builtin: &lt;name&gt; 挂载 */
    public static final List<BuiltinTool> BUILTIN =
            List.of(new BuiltinTool("calculator", "精确计算四则运算与百分比，避免模型心算出错", "L0"));

    /**
     * 语义查询工具（主提示词 5.5 节），经 MCP 网关提供。
     * Agent 只要挂了语义包，组装时自动获得这组工具，不需要在 tools 里声明。
     */
    public static final List<BuiltinTool> SEMANTIC = List.of(
            new BuiltinTool("semantic.describe_object", "查询业务对象的定义、字段含义与关系", "L0"),
            new BuiltinTool("semantic.get_metric", "查询指标的口径定义", "L0"),
            new BuiltinTool("semantic.find_rules", "查询与业务对象相关的业务规则", "L0"),
            new BuiltinTool("semantic.search", "在语义包里按关键词检索对象、指标、规则", "L0"));

    public static Optional<BuiltinTool> find(String name) {
        return BUILTIN.stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
