package dev.eab.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.platform.procode.ProjectManifest;
import dev.eab.spec.Json;
import dev.eab.spec.testing.RepoPaths;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * pnpm eab 全流程的端到端测试（ADR 0014）：登录 → init → 把故事 E 的完成态资产放进工程 → validate →
 * push（找回同一条草稿，draftId 回写清单）→ eval（技能先发布，扩展过评测）→ deploy（发布即生效）→ logs；
 * 再走导出 zip → 解包 → 再 push 的回环，证明界面与文件是同一份数据的两种视图。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EabCliEndToEndTest {

    DemoEnvironment env;
    String url;
    String token;
    final HttpClient http = HttpClient.newHttpClient();

    @TempDir
    Path temp;

    /** 最近一条 eab 命令的输出，断言失败时随 as() 展示 */
    String output;

    @BeforeAll
    void start() throws Exception {
        env = new DemoEnvironment();
        url = env.platformUrl();
        token = env.services().identity().login("chengong").token();
    }

    @AfterAll
    void stop() {
        if (env != null) env.close();
    }

    /** 跑一条 eab 命令；--url 与 --session 预置，命令里再传的同名参数以后者为准 */
    int eab(String... args) {
        String[] full = new String[args.length + 4];
        full[0] = "--url";
        full[1] = url;
        full[2] = "--session";
        full[3] = temp.resolve("session.json").toString();
        System.arraycopy(args, 0, full, 4, args.length);
        var bytes = new ByteArrayOutputStream();
        int code = Eab.run(full, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        output = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    /** 以陈工的身份直接调平台接口，验证 CLI 的效果或取导出 zip */
    JsonNode api(String method, String path) throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(url + path))
                        .header("accept", "application/json")
                        .header("authorization", "Bearer " + token)
                        .method(method, HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return Json.MAPPER.readTree(response.body()).path("data");
    }

    @Test
    @DisplayName("扩展工程全流程：登录 → init → 故事 E 资产 → validate → push → eval → deploy → logs → 导出回环")
    void extensionProjectLifecycle() throws Exception {
        assertThat(eab("login", "--user", "chengong")).as(output).isZero();
        assertThat(output).contains("已登录", "chengong");

        Path project = temp.resolve("purchase-order-clerk-ext");
        assertThat(eab(
                        "init",
                        "--template",
                        "extension",
                        "--target",
                        "vendor.pur/purchase-order-clerk",
                        "--name",
                        "purchase-order-clerk-ext",
                        "--dir",
                        project.toString()))
                .as(output)
                .isZero();
        assertThat(project.resolve(ProjectManifest.FILE)).isRegularFile();

        // 用故事 E 的完成态资产替换模板骨架：push 按 YAML 里的 metadata 定位资产，文件名只是工程内的位置
        String extYaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/pur/purchase-order-clerk-ext.yaml"), StandardCharsets.UTF_8);
        String evalYaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/evals/purchase-clerk-ext-smoke.yaml"), StandardCharsets.UTF_8);
        String skillYaml = Files.readString(
                RepoPaths.assets().resolve("tenants/t001/pur/skills/alternative-supplier-lookup.yaml"),
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("assets/purchase-order-clerk-ext.yaml"), extYaml, StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("evals/purchase-order-clerk-ext-smoke.yaml"), evalYaml, StandardCharsets.UTF_8);
        Files.writeString(
                project.resolve("skills/purchase-order-clerk-ext-skill.yaml"), skillYaml, StandardCharsets.UTF_8);

        assertThat(eab("validate", "--dir", project.toString())).as(output).isZero();
        assertThat(output).contains("✓");

        assertThat(eab("push", "--dir", project.toString())).as(output).isZero();
        assertThat(output).contains("新建", "评测集");
        ProjectManifest manifest = ProjectManifest.read(project);
        ProjectManifest.Entry main =
                manifest.asset("assets/purchase-order-clerk-ext.yaml").orElseThrow();
        assertThat(main.ref()).isEqualTo("tenant.t001.pur/purchase-order-clerk-ext");
        assertThat(main.draftId()).isNotBlank();
        ProjectManifest.Entry skill =
                manifest.asset("skills/purchase-order-clerk-ext-skill.yaml").orElseThrow();
        assertThat(skill.ref()).isEqualTo("tenant.t001.skills/alternative-supplier-lookup");
        assertThat(skill.draftId()).isNotBlank();

        // 草稿内容与本地文件逐字一致：文件推上去，界面看到的就是这一份（yaml 在视图顶层）
        assertThat(api("GET", "/studio/drafts/" + main.draftId()).path("yaml").asString())
                .isEqualTo(extYaml);
        assertThat(api("GET", "/studio/drafts/" + main.draftId())
                        .path("evalYaml")
                        .asString())
                .isEqualTo(evalYaml);
        assertThat(api("GET", "/studio/drafts/" + skill.draftId()).path("yaml").asString())
                .isEqualTo(skillYaml);

        assertThat(eab("eval", "--dir", project.toString())).as(output).isZero();
        assertThat(output).contains("技能已发布 tenant.t001.skills/alternative-supplier-lookup", "评测记录");

        assertThat(eab("deploy", "--dir", project.toString(), "--note", "eab 端到端测试"))
                .as(output)
                .isZero();
        assertThat(output).contains("已发布 tenant.t001.pur/purchase-order-clerk-ext");
        assertThat(api("GET", "/registry/assets/tenant.t001.pur/purchase-order-clerk-ext")
                        .path("summary")
                        .path("status")
                        .asString())
                .isEqualTo("published");
        assertThat(api("GET", "/registry/assets/tenant.t001.skills/alternative-supplier-lookup")
                        .path("summary")
                        .path("status")
                        .asString())
                .isEqualTo("published");

        assertThat(eab("logs")).as(output).isZero();
        assertThat(output).doesNotContain("还没有运行记录");
        JsonNode runs = api("GET", "/runs?limit=5");
        assertThat(runs.isArray()).isTrue();
        assertThat(runs.size()).isPositive();
        assertThat(eab("logs", "--trace", runs.get(0).path("traceId").asString()))
                .as(output)
                .isZero();
        assertThat(output).contains("追踪");

        // 回环：界面导出 zip → 解包成新工程 → 再 push，找回的还是同一条草稿，内容不变
        byte[] zip = http.send(
                        HttpRequest.newBuilder(URI.create(url + "/studio/drafts/" + main.draftId() + "/export"))
                                .header("authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .body();
        assertThat(zip.length).isPositive();
        Path exported = temp.resolve("exported");
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path file = exported.resolve(entry.getName());
                Files.createDirectories(file.getParent());
                Files.write(file, in.readAllBytes());
            }
        }
        assertThat(eab("push", "--dir", exported.toString())).as(output).isZero();
        assertThat(output).contains("更新 assets/purchase-order-clerk-ext.yaml");
        // 发布会把草稿 YAML 规范化重写（版本号写回，P6 起的行为），导出的是当时的草稿原文。
        // 回环保证两件事：导出文件推回去，草稿内容与文件逐字一致；语义与最初的故事 E 资产相同
        String exportedYaml =
                Files.readString(exported.resolve("assets/purchase-order-clerk-ext.yaml"), StandardCharsets.UTF_8);
        assertThat(api("GET", "/studio/drafts/" + main.draftId()).path("yaml").asString())
                .isEqualTo(exportedYaml);
        assertThat(Json.YAML.readTree(exportedYaml)).isEqualTo(Json.YAML.readTree(extYaml));

        // 拉取注册中心里的标品 Agent 成本地工程（租户视角，标品指令为占位）
        assertThat(eab(
                        "pull",
                        "vendor.pur/purchase-order-clerk",
                        "--dir",
                        temp.resolve("pulled").toString()))
                .as(output)
                .isZero();
        assertThat(temp.resolve("pulled/assets/purchase-order-clerk.yaml")).isRegularFile();
        assertThat(temp.resolve("pulled/" + ProjectManifest.FILE)).isRegularFile();
    }

    @Test
    @DisplayName("错误路径：未登录、坏 YAML、业务用户无权、外部 Agent 没在运行")
    void errorPaths() throws Exception {
        // 会话文件不存在：提示先登录，而不是堆栈
        assertThat(eab("logs", "--session", temp.resolve("missing-session.json").toString()))
                .as(output)
                .isEqualTo(1);
        assertThat(output).contains("还没有登录");

        // 坏 YAML：validate 报出问题并以 1 退出
        assertThat(eab("login", "--user", "chengong")).as(output).isZero();
        Path broken = temp.resolve("broken-ext");
        assertThat(eab(
                        "init",
                        "--template",
                        "extension",
                        "--target",
                        "vendor.pur/purchase-order-clerk",
                        "--name",
                        "broken-ext",
                        "--dir",
                        broken.toString()))
                .as(output)
                .isZero();
        Files.writeString(broken.resolve("assets/broken-ext.yaml"), "key: [未闭合", StandardCharsets.UTF_8);
        assertThat(eab("validate", "--dir", broken.toString())).as(output).isEqualTo(1);
        assertThat(output).contains("✗", "有资产没通过校验");

        // 业务用户不是开发者：Studio 的门禁挡下，错误码带出来
        assertThat(eab(
                        "login",
                        "--user",
                        "zhangmin",
                        "--session",
                        temp.resolve("zhangmin.json").toString()))
                .as(output)
                .isZero();
        Path byZhangmin = temp.resolve("zhangmin-ext");
        assertThat(eab(
                        "init",
                        "--template",
                        "extension",
                        "--target",
                        "vendor.pur/purchase-order-clerk",
                        "--name",
                        "zhangmin-ext",
                        "--dir",
                        byZhangmin.toString()))
                .as(output)
                .isZero();
        assertThat(eab(
                        "push",
                        "--dir",
                        byZhangmin.toString(),
                        "--session",
                        temp.resolve("zhangmin.json").toString()))
                .as(output)
                .isEqualTo(1);
        assertThat(output).contains("FORBIDDEN");

        // a2a 工程部署：外部 Agent 没在运行，接入失败而不是误发布
        Path external = temp.resolve("risk-probe");
        assertThat(eab("init", "--template", "a2a-python", "--name", "risk-probe", "--dir", external.toString()))
                .as(output)
                .isZero();
        assertThat(eab("deploy", "--dir", external.toString())).as(output).isEqualTo(1);
        assertThat(output).doesNotContain("已发布");
    }
}
