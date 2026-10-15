package dev.eab.spec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.AssetParser.AssetIssue;
import dev.eab.spec.AssetParser.ParseResult;
import dev.eab.spec.model.Asset.AgentAsset;
import dev.eab.spec.model.Asset.AgentExtensionAsset;
import dev.eab.spec.model.Common.Trigger;
import dev.eab.spec.testing.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssetParserTest {

    static final String AGENT = """
            apiVersion: eab.dev/v1
            kind: Agent
            metadata:
              name: transaction-anomaly-analyst
              namespace: vendor.fin
              version: 1.2.0
              displayName: 交易异常解析 Agent
            spec:
              expertise: 分析交易异常的根因
              instructions: |
                先判断异常类型，再定位根因。
              constraints: [FIN-R-001, 不对未核实的数据下结论]
              model: {profile: reasoning, temperature: 0.2}
              tools:
                - skill: vendor.fin.skills/invoice-lookup
                - api: {source: vendor.erp.tools/demo-erp-bo, tools: [anomaly_list]}
              semanticPacks: [vendor.fin/finance]
              writePolicy: {maxLevel: L1}
              routing:
                description: 分析交易异常的根因
              extensionPoints:
                - type: instructions.append
                  description: 追加本企业的分析口径
                - type: hooks
                  description: 结果产出后触发后续流程
                  events: [onResult]
            """;

    static AssetIssue issueAt(ParseResult result, String path) {
        return result.issues().stream()
                .filter(i -> i.path().equals(path))
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("解析合法的 Agent 并填充默认值")
    void parsesAgentWithDefaults() {
        ParseResult result = AssetParser.parseYaml(AGENT);
        assertThat(result.issues()).isEmpty();
        AgentAsset agent = (AgentAsset) result.asset();
        assertThat(agent.spec().channels()).containsExactly("assistant-web");
        assertThat(agent.spec().triggers()).containsExactly(new Trigger("assistant", null, null));
        assertThat(agent.spec().routing().direct()).isFalse();
        assertThat(agent.spec().model().temperature()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("缺少必填字段时定位到所在的父对象并给出中文说明")
    void missingFieldLocatesParent() {
        ParseResult result = AssetParser.parseYaml(AGENT.replace("  writePolicy: {maxLevel: L1}\n", ""));
        assertThat(result.ok()).isFalse();
        AssetIssue issue = issueAt(result, "spec.writePolicy");
        assertThat(issue).isNotNull();
        assertThat(issue.line()).isEqualTo(9);
        assertThat(issue.message()).containsPattern("[一-龥]");
    }

    @Test
    @DisplayName("嵌套字段出错时报告准确的行号和路径")
    void nestedIssueHasLineAndPath() {
        ParseResult result = AssetParser.parseYaml(
                AGENT.replace("anomaly_list]}", "anomaly_list]}\n    - mcp: {source: not-a-ref}"));
        AssetIssue issue = issueAt(result, "spec.tools[2].mcp.source");
        assertThat(issue).isNotNull();
        assertThat(issue.line()).isEqualTo(17);
        assertThat(issue.format("agent.yaml"))
                .matches("^agent\\.yaml 第 17 行第 \\d+ 列 spec\\.tools\\[2\\]\\.mcp\\.source：.*");
    }

    @Test
    @DisplayName("工具条目同时写两种来源时报错")
    void toolEntryWithTwoSources() {
        ParseResult result = AssetParser.parseYaml(AGENT.replace(
                "- skill: vendor.fin.skills/invoice-lookup", "- {skill: vendor.fin.skills/a, agent: vendor.fin/b}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.issues().getFirst().message()).contains("只能写一种来源");
    }

    @Test
    @DisplayName("拒绝拼错的字段名")
    void rejectsMisspelledField() {
        ParseResult result = AssetParser.parseYaml(AGENT.replace("expertise:", "expertize:"));
        assertThat(result.ok()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.message().contains("expertize"));
    }

    @Test
    @DisplayName("YAML 语法错误带行号")
    void syntaxErrorHasLine() {
        ParseResult result = AssetParser.parseYaml("apiVersion: eab.dev/v1\nkind: Agent\nmetadata: {name: [\n");
        assertThat(result.issues().getFirst().code()).isEqualTo("YAML_SYNTAX");
        assertThat(result.issues().getFirst().line()).isPositive();
    }

    @ParameterizedTest(name = "非法文档 {0} 报 {1}")
    @CsvSource(
            delimiterString = "=>",
            value = {
                "''=>EMPTY_DOCUMENT",
                "'a: 1\n---\nb: 2\n'=>MULTIPLE_DOCUMENTS",
                "'- 1\n'=>NOT_AN_OBJECT",
                "'apiVersion: v0\nkind: Agent\n'=>UNSUPPORTED_API_VERSION",
                "'apiVersion: eab.dev/v1\nkind: Robot\n'=>UNKNOWN_KIND"
            })
    void rejectsInvalidDocuments(String text, String code) {
        ParseResult result = AssetParser.parseYaml(text.replace("\\n", "\n"));
        assertThat(result.issues().getFirst().code()).isEqualTo(code);
    }

    static Map<String, Object> extension(String namespace, Map<String, Object> spec) {
        return Map.of(
                "apiVersion",
                "eab.dev/v1",
                "kind",
                "AgentExtension",
                "metadata",
                Map.of("name", "ext", "namespace", namespace, "version", "0.1.0"),
                "spec",
                spec);
    }

    @Test
    @DisplayName("扩展只能放在租户命名空间")
    void extensionMustBeTenantLayer() {
        ParseResult result = AssetParser.parseObject(Fixtures.tree(
                extension("vendor.fin", Map.of("target", "vendor.fin/transaction-anomaly-analyst@^1.0"))));
        AssetIssue issue = result.issues().getFirst();
        assertThat(issue.code()).isEqualTo("KIND_LAYER_MISMATCH");
        assertThat(issue.path()).isEqualTo("metadata.namespace");
    }

    @Test
    @DisplayName("扩展的 target 必须带版本范围，钩子可以写单个对象")
    void extensionTargetAndSingleHook() {
        ParseResult noRange = AssetParser.parseObject(Fixtures.tree(
                extension("tenant.t001.finance", Map.of("target", "vendor.fin/transaction-anomaly-analyst"))));
        assertThat(noRange.ok()).isFalse();

        ParseResult ok = AssetParser.parseObject(Fixtures.tree(extension(
                "tenant.t001.finance",
                Map.of(
                        "target",
                        "vendor.fin/transaction-anomaly-analyst@^1.0",
                        "hooks",
                        Map.of(
                                "onResult",
                                Map.of(
                                        "workflow",
                                        "tenant.t001.wf/create-followup-task",
                                        "when",
                                        "result.severity == 'high'"))))));
        assertThat(ok.issues()).isEmpty();
        AgentExtensionAsset ext = (AgentExtensionAsset) ok.asset();
        assertThat(ext.spec().hooks().onResult()).hasSize(1);
    }

    @Test
    @DisplayName("扩展 target 不是合法引用时报 schema 问题而不是抛异常")
    void invalidTargetReportsIssue() {
        ParseResult result = AssetParser.parseObject(Fixtures.tree(Map.of(
                "apiVersion",
                "eab.dev/v1",
                "kind",
                "AgentExtension",
                "metadata",
                Map.of("name", "x", "namespace", "tenant.t001", "version", "1.0.0"),
                "spec",
                Map.of("target", "nope"))));
        assertThat(result.issues().getFirst().path()).isEqualTo("spec.target");
    }

    @Test
    @DisplayName("规范化后的内容可以原样再解析，记录类型与 schema 一致")
    void normalizedRoundTrips() {
        AgentAsset agent = Fixtures.agent();
        ParseResult again = AssetParser.parseObject(Json.toTree(agent));
        assertThat(again.issues()).isEmpty();
        assertThat(again.asset()).isEqualTo(agent);
        assertThat(List.of(again.asset().versionedRef()))
                .containsExactly("vendor.fin/transaction-anomaly-analyst@1.0.0");
    }
}
