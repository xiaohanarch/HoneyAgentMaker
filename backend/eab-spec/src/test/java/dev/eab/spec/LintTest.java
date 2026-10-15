package dev.eab.spec;

import static dev.eab.spec.testing.Fixtures.with;
import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.Lint.LintIssue;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.Fixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LintTest {

    static InMemoryAssetResolver resolverWith(Asset... assets) {
        return InMemoryAssetResolver.published(assets);
    }

    static Asset[] base(Asset... extra) {
        List<Asset> list = new ArrayList<>(List.of(
                Fixtures.agent(),
                Fixtures.semanticPack(),
                Fixtures.skill(),
                Fixtures.toolSource(),
                Fixtures.workflow()));
        list.addAll(List.of(extra));
        return list.toArray(Asset[]::new);
    }

    static List<String> rules(List<LintIssue> issues) {
        return issues.stream().map(LintIssue::rule).toList();
    }

    @Nested
    class NoHardcodedEndpoint {
        @Test
        @DisplayName("资产里写地址或凭据时报错")
        void flagsEndpointsAndSecrets() {
            var agent = Fixtures.agent(with().spec("instructions", "查询 http://10.0.0.8:8080/bo 接口")
                    .spec("additionalContext", "密钥 sk-abcdefghijklmnopqrstu"));
            var paths = Lint.lint(agent, resolverWith(base())).stream()
                    .filter(i -> i.rule().equals("no-hardcoded-endpoint"))
                    .map(LintIssue::path)
                    .toList();
            assertThat(paths).containsExactly("spec.instructions", "spec.additionalContext");
        }

        @Test
        @DisplayName("凭据字段只能写 env: 或 vault: 引用")
        void credentialFieldsMustBeReferences() {
            var bad = Fixtures.skill(with().spec(
                            "steps",
                            List.of(Map.of(
                                    "id", "s", "type", "tool", "tool", "x", "with", Map.of("token", "abc123")))));
            assertThat(rules(Lint.lint(bad, resolverWith()))).contains("no-hardcoded-endpoint");
            var ok = Fixtures.skill(with().spec(
                            "steps",
                            List.of(Map.of(
                                    "id", "s", "type", "tool", "tool", "x", "with", Map.of("token", "env:IM_TOKEN")))));
            assertThat(rules(Lint.lint(ok, resolverWith()))).doesNotContain("no-hardcoded-endpoint");
        }

        @Test
        @DisplayName("连接的环境绑定允许写地址")
        void connectionBindingsMayHaveUrls() {
            var connection = Fixtures.build(
                    "Connection",
                    "demo-erp",
                    "tenant.t001.connections",
                    Map.of(
                            "alias", "DEMO_ERP",
                            "type", "bo-api",
                            "bindings",
                                    Map.of(
                                            "dev",
                                            Map.of(
                                                    "url",
                                                    "http://127.0.0.1:4100",
                                                    "auth",
                                                    Map.of("type", "token-exchange")))));
            assertThat(Lint.lint(connection, resolverWith())).isEmpty();
        }
    }

    @Nested
    class ToolWriteLevel {
        @Test
        @DisplayName("工具没有声明写级别时报错")
        void missingWriteLevel() {
            var source = Fixtures.toolSource(
                    with().spec("tools", List.of(Map.of("name", "notify_im", "description", "通知"))));
            assertThat(rules(Lint.lint(source, resolverWith()))).containsExactly("tool-write-level");
        }

        @Test
        @DisplayName("付款类工具最高只能到 L2")
        void paymentCappedAtL2() {
            var source = Fixtures.toolSource(with().spec(
                            "tools",
                            List.of(Map.of(
                                    "name", "pay", "description", "付款", "writeLevel", "L3", "category", "payment"))));
            assertThat(Lint.lint(source, resolverWith()).getFirst().message()).contains("最高只能到 L2");
        }
    }

    @Nested
    class ExtensionPointDeclared {
        @Test
        @DisplayName("扩展只用了已声明的扩展点时没有问题")
        void declaredPointsPass() {
            assertThat(Lint.lint(Fixtures.extension(), resolverWith(base()))).isEmpty();
        }

        @Test
        @DisplayName("使用未声明的扩展点、未允许的钩子事件或模型档位时报错")
        void undeclaredPointsFail() {
            var narrowAgent = Fixtures.agent(with().spec(
                            "extensionPoints",
                            List.of(
                                    Map.of(
                                            "type",
                                            "hooks",
                                            "description",
                                            "只允许 onResult",
                                            "events",
                                            List.of("onResult")),
                                    Map.of(
                                            "type",
                                            "model.select",
                                            "description",
                                            "模型",
                                            "allowedProfiles",
                                            List.of("reasoning")))));
            var ext = Fixtures.extension(with().spec("instructions", Map.of("append", "追加"))
                    .spec("hooks", Map.of("onFailure", Map.of("workflow", "tenant.t001.wf/create-followup-task")))
                    .spec("model", Map.of("profile", "fast")));
            var messages =
                    Lint.lint(ext, resolverWith(narrowAgent, Fixtures.semanticPack(), Fixtures.workflow())).stream()
                            .filter(i -> i.rule().equals("extension-point-declared"))
                            .map(LintIssue::message)
                            .toList();
            assertThat(messages)
                    .containsExactly(
                            "目标 Agent 没有声明扩展点 instructions.append",
                            "目标 Agent 的 hooks 扩展点不允许挂在 onFailure 事件上",
                            "目标 Agent 只允许选择模型档位 reasoning，不能选 fast");
        }
    }

    @Nested
    class ReferenceResolvable {
        @Test
        @DisplayName("引用不存在、版本不满足或 kind 不符时报错")
        void unresolvedReferences() {
            var agent = Fixtures.agent(with().spec(
                            "tools",
                            List.of(
                                    Map.of("skill", "vendor.fin.skills/invoice-lookup@^2"),
                                    Map.of("agent", "vendor.fin/finance"),
                                    Map.of("builtin", "teleport")))
                    .spec("semanticPacks", List.of("vendor.fin/finance", "vendor.fin/missing")));
            var messages = Lint.lint(agent, resolverWith(base())).stream()
                    .map(LintIssue::message)
                    .toList();
            assertThat(messages)
                    .containsExactly(
                            "引用的资产 vendor.fin.skills/invoice-lookup@^2 不存在，或者没有满足 ^2 的版本",
                            "引用的资产 vendor.fin/finance 是 SemanticPack，这里需要 Agent",
                            "引用的资产 vendor.fin/missing 不存在",
                            "内置工具 teleport 不存在");
        }

        @Test
        @DisplayName("约束引用的规则编号必须在语义包里存在，扩展可以引用自己追加的语义包")
        void ruleIdsMustExist() {
            var agent = Fixtures.agent(with().spec("constraints", List.of("FIN-R-001", "FIN-R-404")));
            assertThat(Lint.lint(agent, resolverWith(base())).stream().map(LintIssue::path))
                    .containsExactly("spec.constraints[1]");

            var tenantPack = Fixtures.semanticPack(with().name("finance-ext")
                    .namespace("tenant.t001.semantic")
                    .spec("extends", "vendor.fin/finance")
                    .spec("rules", List.of(Map.of("id", "FIN-R-101", "severity", "warn", "remedy", "跨期冲销不算异常"))));
            var ext = Fixtures.extension(with().spec("constraints", Map.of("append", List.of("FIN-R-101", "FIN-R-001")))
                    .spec("knowledge", Map.of("add", List.of("tenant.t001.semantic/finance-ext"))));
            assertThat(Lint.lint(ext, resolverWith(base(tenantPack)))).isEmpty();
        }

        @Test
        @DisplayName("租户资产不能引用其他租户的资产")
        void referenceScope() {
            var ext = Fixtures.extension(with().spec("skills", Map.of("add", List.of("tenant.t002.skills/peek"))));
            var issues = Lint.lint(ext, resolverWith(base()));
            assertThat(rules(issues)).contains("reference-scope");
            // 越界的引用不进入解析，不会因为“不存在”而泄露信息
            assertThat(issues)
                    .noneMatch(i -> i.rule().equals("reference-resolvable")
                            && i.message().contains("t002"));
        }
    }
}
