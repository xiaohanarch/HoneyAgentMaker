package dev.eab.assembler;

/**
 * 组装中发现的问题。error 阻止组装；warning 不阻止，但发布门禁会拦下。
 *
 * @param code AGENT_NOT_FOUND、TARGET_UNRESOLVED、EXTENSION_POINT_UNDECLARED、TOOL_NAME_CONFLICT、
 *     APPROVAL_RELAXED、MODEL_NOT_ALLOWED、REFERENCE_UNRESOLVED、HOOK_CONDITION_INVALID、
 *     APPROVAL_CONDITION_INVALID、TOOL_WRITE_LEVEL_EXCEEDED 之一
 * @param source 出问题的资产
 */
public record AssemblyIssue(String code, String severity, String message, String source, String path) {

    public static AssemblyIssue error(String code, String message, String source, String path) {
        return new AssemblyIssue(code, "error", message, source, path);
    }

    public static AssemblyIssue warning(String code, String message, String source, String path) {
        return new AssemblyIssue(code, "warning", message, source, path);
    }

    public boolean isError() {
        return severity.equals("error");
    }
}
