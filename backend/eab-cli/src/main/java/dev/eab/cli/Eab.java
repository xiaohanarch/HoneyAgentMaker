package dev.eab.cli;

import dev.eab.platform.procode.ProjectExport;
import dev.eab.platform.procode.ProjectManifest;
import dev.eab.platform.procode.ProjectTemplates;
import dev.eab.spec.AssetRef;
import dev.eab.spec.Json;
import dev.eab.tracing.SpanRecord;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * pnpm eab：专业代码通道的命令行（ADR 0014）。资产是工程里的 YAML 文件：init 从模板建工程，pull 把草稿或资产拉成本地文件，
 * push 找回（或新建）同一条草稿再整份替换，validate 在平台侧批量校验（工程内资产相互引用时一起解析），eval 与 deploy 走
 * Studio 的评测与发布门禁，a2a 工程的 deploy 经 Studio 的接入通道把运行中的外部 Agent 登记为远程 Agent，logs 看运行与追踪。
 *
 * <p>退出码：0 成功，1 操作失败，2 用法不对。会话（令牌）保存在仓库外的 ~/.config/eab/cli-session.json。
 */
public final class Eab {

    private Eab() {}

    static final String USAGE = """
            eab：把资产当作代码——拉到本地、放进 Git、推回平台。

            用法：pnpm eab <子命令> [参数]
              pnpm eab login   --user <用户> [--url <平台地址>] [--print-token]
              pnpm eab init    --template agent|extension|a2a-python|a2a-ts --name <名称>
                               [--target <标品Agent>] [--dir <目录>]
              pnpm eab pull    <namespace/name>[@版本] 或 --draft <草稿编号> [--dir <目录>]
              pnpm eab push    [--dir <目录>]
              pnpm eab validate [--dir <目录>]
              pnpm eab eval    [--dir <目录>] [--generate] [--user <测试用户>]
              pnpm eab deploy  [--dir <目录>] [--note <说明>] [--promote test|prod]
              pnpm eab logs    [--agent <namespace/name>] [--limit 20] [--trace <追踪编号>]

            公共参数：--url 平台地址（默认读会话，再默认 http://127.0.0.1:4000）；--session 会话文件；--dir 工程目录
            """;

    public static void main(String[] argv) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        // scripts/java-cli.mjs 经环境变量传一份参数：Windows 上命令行参数里的中文会被 ANSI 代码页弄乱
        String fromEnv = System.getenv("EAB_CLI_ARGS");
        if (fromEnv != null && !fromEnv.isBlank()) argv = Json.MAPPER.readValue(fromEnv, String[].class);
        System.exit(run(argv, out));
    }

    /** 入口包一层，测试直接调这里拿退出码 */
    static int run(String[] argv, PrintStream out) {
        try {
            Opts opts = Opts.parse(argv);
            if (opts.rest().isEmpty() || opts.rest().getFirst().equals("help")) throw usage(USAGE);
            switch (opts.rest().getFirst()) {
                case "login" -> login(opts, out);
                case "init" -> init(opts, out);
                case "pull" -> pull(opts, out);
                case "push" -> push(opts, out);
                case "validate" -> validate(opts, out);
                case "eval" -> eval(opts, out);
                case "deploy" -> deploy(opts, out);
                case "logs" -> logs(opts, out);
                default -> throw usage("不认识的子命令：" + opts.rest().getFirst() + "\n" + USAGE);
            }
            return 0;
        } catch (CliException e) {
            out.println(e.exit() == 2 ? e.getMessage() : red(e.getMessage()));
            return e.exit();
        } catch (Exception e) {
            out.println(red("命令执行失败：" + e));
            return 1;
        }
    }

    // ---------------------------------------------------------------- login / init

    static void login(Opts opts, PrintStream out) {
        String user = opts.require("user", "演示用户，例如 chengong");
        String url = resolveUrl(opts, null, null);
        JsonNode data = new Api(url, null).post("/identity/v1/login", Map.of("userId", user));
        JsonNode who = data.path("user");
        Session session = new Session(
                url,
                who.path("id").asString(user),
                who.path("tenantId").asString(null),
                data.path("token").asString(null));
        if (session.token() == null) throw CliException.failure("登录响应里没有令牌");
        Path file = sessionFile(opts);
        writeSession(session, file);
        out.println("已登录 " + who.path("name").asString(user) + "（" + session.user() + "，" + url + "），会话在 " + file);
        if (opts.opt("print-token") != null) out.println(session.token());
    }

    static void init(Opts opts, PrintStream out) {
        String template = opts.opt("template");
        if (template == null) throw usage("缺少 --template：agent、extension、a2a-python、a2a-ts");
        String name = opts.require("name", "工程与资产名，小写字母、数字、连字符");
        Path dir = Path.of(opts.opt("dir", name));
        try {
            ProjectManifest manifest =
                    ProjectTemplates.init(template, name, opts.opt("target"), resolveUrl(opts, null, null), dir);
            out.println("已创建工程 " + dir + "（模板 " + manifest.template() + "）");
            out.println("下一步：cd " + dir + "，编辑 assets/ 下的 YAML，然后 pnpm eab validate");
        } catch (IllegalArgumentException e) {
            throw usage(e.getMessage());
        } catch (UncheckedIOException e) {
            throw CliException.failure("工程文件写不出 " + dir + "：" + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- pull

    static void pull(Opts opts, PrintStream out) {
        Session session = readSession(opts);
        Api api = new Api(resolveUrl(opts, session, null), session.token());
        if (opts.opt("draft") != null) {
            JsonNode view = api.get("/studio/drafts/" + opts.opt("draft"));
            JsonNode draft = view.path("draft");
            Path dir = Path.of(opts.opt("dir", draft.path("name").asString("draft")));
            Map<String, String> files;
            try {
                files = draftFiles(view);
            } catch (IllegalArgumentException e) {
                throw CliException.failure(e.getMessage());
            }
            writeProject(dir, files, out);
            out.println("已拉取草稿 " + draft.path("id").asString() + " 到 " + dir);
            stamp(dir, session);
            return;
        }
        String ref = positional(opts, 1, "用法：pnpm eab pull <namespace/name>[@版本] 或 --draft <草稿编号>");
        String[] withVersion = ref.split("@", 2);
        String[] parts = withVersion[0].split("/", 2);
        if (parts.length != 2) throw usage("资产引用写作 namespace/name，例如 vendor.pur/purchase-order-clerk");
        String path = "/registry/assets/" + parts[0] + "/" + parts[1]
                + (withVersion.length == 2
                        ? "?version=" + URLEncoder.encode(withVersion[1], StandardCharsets.UTF_8)
                        : "");
        JsonNode view = api.get(path);
        JsonNode asset = view.path("asset");
        String kind =
                view.path("summary").path("kind").asString(asset.path("kind").asString(""));
        if (!List.of("Agent", "AgentExtension", "Skill").contains(kind)) {
            throw CliException.failure(
                    "暂不支持拉取 " + kind + " 资产；评测集随主资产走（pnpm eab pull --draft <草稿编号>，或在 Studio 里导出代码工程）");
        }
        String name = asset.path("metadata").path("name").asString(parts[1]);
        Map<String, String> files = ProjectExport.files(
                kind,
                name,
                asset.path("metadata").path("owner").asString(null),
                parts[0] + "/" + name,
                null,
                Json.YAML.writeValueAsString(asset),
                null);
        Path dir = Path.of(opts.opt("dir", name));
        writeProject(dir, files, out);
        out.println("已拉取 " + parts[0] + "/" + name + " 到 " + dir);
        stamp(dir, session);
    }

    /** 草稿视图 → 工程文件集：走 ProjectExport，与导出 zip 的产物逐字节一致 */
    static Map<String, String> draftFiles(JsonNode view) {
        JsonNode draft = view.path("draft");
        JsonNode eval = view.get("evalYaml");
        String name = draft.path("name").asString("");
        String namespace = draft.path("namespace").asString("");
        return ProjectExport.files(
                draft.path("kind").asString(""),
                name,
                draft.path("owner").asString(null),
                namespace.isBlank() ? name : namespace + "/" + name,
                draft.path("id").asString(),
                view.path("yaml").asString(),
                eval == null || eval.isNull() ? null : eval.asString());
    }

    /** 写工程文件（pull 会覆盖同名文件）并逐个打印 */
    static void writeProject(Path dir, Map<String, String> files, PrintStream out) {
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (file.getKey().contains("..") || file.getKey().startsWith("/")) {
                throw CliException.failure("拒绝写出工程外的路径：" + file.getKey());
            }
            Path target = dir.resolve(file.getKey());
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw CliException.failure("写不出 " + file.getKey() + "：" + e.getMessage());
            }
            out.println("  " + file.getKey());
        }
    }

    /** 把平台地址与登录用户写进刚拉取的工程清单，后续命令不用再带 --url */
    static void stamp(Path dir, Session session) {
        ProjectManifest manifest = manifest(dir);
        if (session.url().equals(manifest.platform()) && session.user().equals(manifest.user())) return;
        writeManifest(
                dir,
                new ProjectManifest(
                        manifest.template(),
                        session.url(),
                        session.user(),
                        manifest.target(),
                        manifest.cardUrl(),
                        manifest.assets()));
    }

    // ---------------------------------------------------------------- push

    /** push 的结果：主草稿条目与全部技能条目，eval 和 deploy 在这基础上继续 */
    record Pushed(
            ProjectManifest manifest,
            String mainKind,
            ProjectManifest.Entry main,
            List<ProjectManifest.Entry> skills) {}

    static void push(Opts opts, PrintStream out) {
        Path dir = projectDir(opts);
        ProjectManifest manifest = manifest(dir);
        Session session = readSession(opts);
        Api api = new Api(resolveUrl(opts, session, manifest), session.token());
        pushProject(dir, manifest, session, api, out);
    }

    /** 本地文件 → 平台草稿：每份资产找回（或新建）同一条草稿再整份替换，draftId 回写清单 */
    static Pushed pushProject(Path dir, ProjectManifest manifest, Session session, Api api, PrintStream out) {
        if (manifest.isA2a()) {
            out.println(dim("a2a 工程没有平台侧资产；pnpm eab deploy 会读取运行中的卡片并接入"));
            return new Pushed(manifest, null, null, List.of());
        }
        String kind = mainKindOf(manifest.template());
        ProjectManifest updated = manifest;
        ProjectManifest.Entry main = null;
        List<ProjectManifest.Entry> skills = new ArrayList<>();
        for (ProjectManifest.Entry entry : manifest.assets()) {
            if (entry.kind().equals("EvalSuite")) continue;
            ProjectManifest.Entry pushed = pushAsset(dir, entry, api, out);
            updated = updated.withAsset(pushed);
            if (kind != null && entry.kind().equals(kind)) main = pushed;
            if (entry.kind().equals("Skill")) skills.add(pushed);
        }
        if (main == null) throw CliException.failure("清单里没有 " + kind + " 资产条目，不知道推给哪条草稿");
        for (ProjectManifest.Entry entry : updated.assets()) {
            if (!entry.kind().equals("EvalSuite")) continue;
            attachEval(dir, entry, main, kind, api, out);
            updated = updated.withAsset(
                    new ProjectManifest.Entry(entry.file(), entry.kind(), entry.ref(), main.draftId()));
        }
        if (!updated.toYaml().equals(manifest.toYaml())) writeManifest(dir, updated);
        return new Pushed(updated, kind, main, skills);
    }

    /** 推一份资产文件：定位草稿 → 整份替换 YAML；保存有问题（schema 等）就停下 */
    static ProjectManifest.Entry pushAsset(Path dir, ProjectManifest.Entry entry, Api api, PrintStream out) {
        String yaml = readFile(dir, entry.file());
        JsonNode meta = parseMetadata(yaml, entry.file());
        String namespace = meta.path("metadata").path("namespace").asString(null);
        String name = meta.path("metadata").path("name").asString(null);
        if (namespace == null || name == null) {
            throw CliException.failure(entry.file() + " 的 metadata 里缺 namespace 或 name");
        }
        String draftId = findDraft(api, entry, entry.kind(), namespace, name);
        boolean created = draftId == null;
        if (created) draftId = createDraft(api, entry, meta, entry.kind(), namespace, name);
        JsonNode view = api.put("/studio/drafts/" + draftId + "/yaml", Map.of("yaml", yaml));
        if (view.path("issues").isArray() && !view.path("issues").isEmpty()) {
            StringBuilder text = new StringBuilder(entry.file() + " 保存时有问题：");
            for (JsonNode issue : view.path("issues")) text.append("\n  ").append(issueText(issue));
            throw CliException.failure(text.toString());
        }
        out.println((created ? "  新建 " : "  更新 ") + entry.file() + " → 草稿 " + draftId
                + dim("（" + namespace + "/" + name + "）"));
        return new ProjectManifest.Entry(entry.file(), entry.kind(), namespace + "/" + name, draftId);
    }

    /** 找已有草稿：先试清单里的 draftId（404 视为草稿已删），再按 kind + namespace + name 匹配 */
    static String findDraft(Api api, ProjectManifest.Entry entry, String kind, String namespace, String name) {
        if (entry.draftId() != null && !entry.draftId().isBlank()) {
            try {
                JsonNode draft = api.get("/studio/drafts/" + entry.draftId()).path("draft");
                if (kind.equals(draft.path("kind").asString())
                        && name.equals(draft.path("name").asString())) {
                    return entry.draftId();
                }
            } catch (CliException e) {
                if (e.status() != 404) throw e;
            }
        }
        JsonNode drafts = api.get("/studio/home").path("drafts");
        if (drafts.isArray()) {
            for (JsonNode draft : drafts) {
                if (kind.equals(draft.path("kind").asString())
                        && namespace.equals(draft.path("namespace").asString())
                        && name.equals(draft.path("name").asString())) {
                    return draft.path("id").asString();
                }
            }
        }
        return null;
    }

    /** 建新草稿：按资产类型走三条建稿通道（Agent 建稿、扩展按目标建稿、技能建稿） */
    static String createDraft(
            Api api, ProjectManifest.Entry entry, JsonNode meta, String kind, String namespace, String name) {
        JsonNode view;
        switch (kind) {
            case "Agent" -> view = api.post("/studio/drafts", Map.of("intent", "eab push：" + namespace + "/" + name));
            case "AgentExtension" -> {
                String target = meta.path("spec").path("target").asString(null);
                if (target == null) throw CliException.failure(entry.file() + " 的 spec 里没有 target，扩展草稿建不起来");
                view = api.post(
                        "/studio/extensions",
                        Map.of("target", AssetRef.parse(target).key()));
            }
            case "Skill" -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("name", name);
                body.put("displayName", meta.path("spec").path("displayName").asString(name));
                view = api.post("/studio/skills", body);
            }
            default ->
                throw CliException.failure("清单条目 " + entry.file() + " 的 kind " + kind + " 不能推送（EvalSuite 随主资产走）");
        }
        String id = view.path("draft").path("id").asString(null);
        if (id == null) throw CliException.failure("创建 " + kind + " 草稿失败：平台没有返回草稿编号");
        return id;
    }

    /** 评测集不是独立资产：推到主草稿的评测槽 */
    static void attachEval(
            Path dir,
            ProjectManifest.Entry entry,
            ProjectManifest.Entry main,
            String mainKind,
            Api api,
            PrintStream out) {
        if (!mainKind.equals("Agent") && !mainKind.equals("AgentExtension")) {
            throw CliException.failure("技能工程没有评测集，请从清单里去掉 " + entry.file());
        }
        String slot = mainKind.equals("Agent") ? "/evals" : "/extension/evals";
        api.put("/studio/drafts/" + main.draftId() + slot, Map.of("yaml", readFile(dir, entry.file())));
        out.println("  更新 " + entry.file() + " → 草稿 " + main.draftId() + " 的评测集");
    }

    // ---------------------------------------------------------------- validate

    static void validate(Opts opts, PrintStream out) {
        Path dir = projectDir(opts);
        ProjectManifest manifest = manifest(dir);
        if (manifest.assets().isEmpty()) throw usage("清单里没有资产条目，没有可校验的东西");
        List<String> yamls = new ArrayList<>();
        for (ProjectManifest.Entry entry : manifest.assets()) yamls.add(readFile(dir, entry.file()));
        Session session = readSession(opts);
        Api api = new Api(resolveUrl(opts, session, manifest), session.token());
        JsonNode results = api.post("/registry/validate-batch", Map.of("assets", yamls));
        boolean valid = true;
        for (int i = 0; i < manifest.assets().size(); i++) {
            JsonNode result = results.get(i);
            String file = manifest.assets().get(i).file();
            if (result.path("valid").asBoolean(false)) out.println(green("✓ ") + file);
            else {
                valid = false;
                out.println(red("✗ ") + file);
            }
            for (JsonNode issue : result.path("issues")) out.println(red("    " + issueText(issue)));
            for (JsonNode lint : result.path("lint")) {
                String line = lint.path("rule").asString() + "："
                        + lint.path("message").asString();
                out.println(
                        lint.path("severity").asString().equals("error") ? red("    " + line) : yellow("    " + line));
            }
        }
        if (!valid) throw CliException.failure("有资产没通过校验，先修好再 push");
    }

    // ---------------------------------------------------------------- eval

    static void eval(Opts opts, PrintStream out) {
        Path dir = projectDir(opts);
        ProjectManifest manifest = manifest(dir);
        String kind = mainKindOf(manifest.template());
        if (kind == null) throw usage("a2a 工程没有平台侧资产，不需要评测；外部 Agent 保持运行后 pnpm eab deploy 接入");
        if (kind.equals("Skill")) throw usage("技能工程不需要评测，直接 pnpm eab deploy");
        Session session = readSession(opts);
        Api api = new Api(resolveUrl(opts, session, manifest), session.token());
        Pushed pushed = pushProject(dir, manifest, session, api, out);
        ProjectManifest.Entry main = pushed.main();
        if (opts.opt("generate") != null) generateEvals(dir, pushed.manifest(), main, kind, opts, session, api, out);
        publishSkills(pushed.skills(), api, out);
        JsonNode run = kind.equals("Agent")
                ? api.post("/studio/drafts/" + main.draftId() + "/evals/run", Map.of())
                : api.post("/studio/drafts/" + main.draftId() + "/extension/evals/run", Map.of());
        printEvalRun(run, out);
        if (!"passed".equals(run.path("status").asString())) {
            throw CliException.failure("评测未通过（" + run.path("status").asString() + "）");
        }
    }

    /** 生成评测集初稿：写回本地 evals/，清单里的评测条目换成生成的这份 */
    static void generateEvals(
            Path dir,
            ProjectManifest manifest,
            ProjectManifest.Entry main,
            String kind,
            Opts opts,
            Session session,
            Api api,
            PrintStream out) {
        String testUser = opts.opt("user", manifest.user() == null ? session.user() : manifest.user());
        String slot = kind.equals("Agent") ? "/evals/generate" : "/extension/evals/generate";
        JsonNode view = api.post("/studio/drafts/" + main.draftId() + slot, Map.of("testUser", testUser));
        JsonNode eval = view.get("evalYaml");
        if (eval == null || eval.isNull()) throw CliException.failure("平台没有生成评测集");
        String yaml = eval.asString();
        JsonNode meta = parseMetadata(yaml, "生成的评测集");
        String mainName = main.ref().substring(main.ref().lastIndexOf('/') + 1);
        String evalName = meta.path("metadata").path("name").asString(mainName + "-smoke");
        String file = ProjectTemplates.evalPath(evalName);
        try {
            Path target = dir.resolve(file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw CliException.failure("评测集写不进 " + file + "：" + e.getMessage());
        }
        String namespace = meta.path("metadata").path("namespace").asString(null);
        List<ProjectManifest.Entry> assets = new ArrayList<>();
        for (ProjectManifest.Entry existing : manifest.assets()) {
            if (!existing.kind().equals("EvalSuite")) assets.add(existing);
        }
        assets.add(new ProjectManifest.Entry(
                file, "EvalSuite", namespace == null ? null : namespace + "/" + evalName, main.draftId()));
        writeManifest(
                dir,
                new ProjectManifest(
                        manifest.template(),
                        manifest.platform(),
                        manifest.user(),
                        manifest.target(),
                        manifest.cardUrl(),
                        assets));
        out.println("  已生成 " + file + dim("（测试用户 " + testUser + "）"));
    }

    /** 技能先发布（扩展的评测与发布要求技能已发布）；技能发布按内容幂等 */
    static void publishSkills(List<ProjectManifest.Entry> skills, Api api, PrintStream out) {
        if (skills.isEmpty()) return;
        out.println(dim("  先发布技能（扩展的评测与发布要求技能已发布，内容不变时沿用已发布版本）"));
        for (ProjectManifest.Entry skill : skills) {
            JsonNode result = api.post("/studio/drafts/" + skill.draftId() + "/skill-publish", Map.of());
            out.println(green("  ✓ 技能已发布 ") + result.path("ref").asString() + "@"
                    + result.path("version").asString()
                    + (result.path("unchanged").asBoolean(false) ? dim("（内容未变，沿用）") : ""));
        }
    }

    /** 打印评测结果：Agent 是单个评测集，扩展分公开与扩展两个套件 */
    static void printEvalRun(JsonNode run, PrintStream out) {
        out.println(dim("  评测记录 " + run.path("id").asString()));
        if (run.path("suites").isArray()) {
            for (JsonNode suite : run.path("suites")) {
                out.println((suite.path("ok").asBoolean(false) ? green("✓ ") : red("✗ "))
                        + suite.path("suite").asString()
                        + dim("  " + suite.path("passed").asInt() + "/"
                                + suite.path("total").asInt() + "，最低通过率 "
                                + suite.path("minPassRate").asDouble()));
                printCases(suite.path("cases"), out);
            }
            return;
        }
        out.println(
                "  通过 " + run.path("passed").asInt() + "/" + run.path("total").asInt()
                        + dim("，最低通过率 " + run.path("minPassRate").asDouble() + "，通过率 "
                                + run.path("passRate").asDouble()));
        printCases(run.path("cases"), out);
    }

    static void printCases(JsonNode cases, PrintStream out) {
        for (JsonNode c : cases) {
            out.println("  " + (c.path("passed").asBoolean(false) ? green("✓ ") : red("✗ "))
                    + c.path("id").asString() + dim("（" + c.path("user").asString() + "）") + " "
                    + c.path("question").asString());
            for (JsonNode a : c.path("assertions")) {
                if (!a.path("passed").asBoolean(false)) {
                    out.println(red("      " + a.path("type").asString() + "："
                            + a.path("detail").asString()));
                }
            }
            if (!c.path("passed").asBoolean(false) && c.has("text")) {
                out.println(dim("      回复：" + truncate(c.path("text").asString(), 160)));
            }
        }
    }

    // ---------------------------------------------------------------- deploy

    static void deploy(Opts opts, PrintStream out) {
        Path dir = projectDir(opts);
        ProjectManifest manifest = manifest(dir);
        Session session = readSession(opts);
        Api api = new Api(resolveUrl(opts, session, manifest), session.token());
        if (manifest.isA2a()) {
            deployA2a(dir, manifest, session, api, out);
            return;
        }
        String kind = mainKindOf(manifest.template());
        if (kind == null) throw usage("不认识的模板：" + manifest.template());
        Pushed pushed = pushProject(dir, manifest, session, api, out);
        publishSkills(nonMainSkills(pushed), api, out);
        String note = opts.opt("note", "eab deploy");
        JsonNode result = switch (kind) {
            case "Agent" ->
                api.post("/studio/drafts/" + pushed.main().draftId() + "/publish", Map.of("changeNote", note));
            case "AgentExtension" ->
                api.post(
                        "/studio/drafts/" + pushed.main().draftId() + "/extension/publish", Map.of("changeNote", note));
            default -> api.post("/studio/drafts/" + pushed.main().draftId() + "/skill-publish", Map.of());
        };
        printPublished(kind, result, out);
        if (opts.opt("promote") != null) promote(opts, kind, result, api, out);
    }

    /** 主资产之外的技能条目：技能工程的 main 本身就是技能，别发布两遍 */
    static List<ProjectManifest.Entry> nonMainSkills(Pushed pushed) {
        return pushed.skills().stream()
                .filter(skill -> !skill.file().equals(pushed.main().file()))
                .toList();
    }

    static void printPublished(String kind, JsonNode result, PrintStream out) {
        String ref = result.path("ref").asString();
        String version = result.path("version").asString();
        String suffix = result.path("unchanged").asBoolean(false) ? dim("（内容与已发布版本一致，沿用 " + version + "）") : "";
        if (kind.equals("Agent")) {
            out.println(green("✓ 已发布 ") + ref + "@" + version
                    + dim("（环境 " + result.path("deployment").path("environment").asString("dev") + "）") + suffix);
        } else if (kind.equals("AgentExtension")) {
            out.println(green("✓ 已发布 ") + ref + "@" + version + dim("（租户扩展发布即生效）") + suffix);
            if (result.path("evalRun").has("status")) {
                out.println(
                        dim("  发布评测：" + result.path("evalRun").path("status").asString() + "，通过 "
                                + result.path("evalRun").path("passed").asInt() + "/"
                                + result.path("evalRun").path("total").asInt()));
            }
        } else {
            out.println(green("✓ 已发布 ") + ref + "@" + version + suffix);
        }
    }

    /** Agent 发布后晋级环境（dev → test → prod），门禁没过时平台的 issues 会逐条带出来 */
    static void promote(Opts opts, String kind, JsonNode published, Api api, PrintStream out) {
        if (!kind.equals("Agent")) throw usage("--promote 只用于 Agent 工程（扩展与技能发布即生效）");
        String environment = opts.require("promote", "test 或 prod");
        if (!List.of("test", "prod").contains(environment)) throw usage("--promote 只能是 test 或 prod");
        api.post(
                "/studio/deployments/promote",
                Map.of(
                        "ref", published.path("ref").asString(),
                        "version", published.path("version").asString(),
                        "environment", environment));
        out.println(green("✓ 已晋级 ") + published.path("ref").asString() + "@"
                + published.path("version").asString() + dim(" → " + environment));
    }

    /**
     * a2a 工程的部署：经 Studio 的接入通道——平台拉取运行中的 Agent Card 给出建议，CLI 用工程目录名当资产名、
     * 按约定起连接别名，一次 publish 同时登记连接与远程 Agent（内容变化时平台自动升补丁版本）。
     */
    static void deployA2a(Path dir, ProjectManifest manifest, Session session, Api api, PrintStream out) {
        if (manifest.cardUrl() == null || manifest.cardUrl().isBlank()) {
            throw CliException.failure("清单里没有 cardUrl，不知道外部 Agent 的卡片地址（" + ProjectManifest.FILE + " 的 cardUrl）");
        }
        if (session.tenant() == null || List.of("vendor", "platform").contains(session.tenant())) {
            throw CliException.failure("接入外部 Agent 需要租户身份，当前用户 " + session.user() + " 不是租户用户");
        }
        String name = dirName(dir);
        JsonNode suggestion = api.post("/studio/external/inspect", Map.of("cardUrl", manifest.cardUrl()))
                .path("suggestion");
        String alias = name.toUpperCase(Locale.ROOT).replace('-', '_') + "_AGENT";
        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put(
                "description", suggestion.path("routing").path("description").asString(name));
        routing.put("examples", strings(suggestion.path("routing").path("examples")));
        routing.put("direct", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cardUrl", manifest.cardUrl());
        body.put("name", name);
        body.put("displayName", suggestion.path("displayName").asString(name));
        body.put("connection", alias);
        body.put("routing", routing);
        body.put("assistant", "");
        body.put("allowedCallers", List.of());
        JsonNode result = api.post("/studio/external/publish", body);
        for (JsonNode ref : result.path("published")) out.println(green("✓ 已发布 ") + ref.asString());
        out.println("远程 Agent " + name + " 已进入能力目录；外部 Agent 保持运行，租户提问即会路由过去");
    }

    /** 工程目录名当资产名：与 init 的 --name 是同一个词 */
    static String dirName(Path dir) {
        Path fileName = dir.toAbsolutePath().normalize().getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (!name.matches("^[a-z][a-z0-9-]{1,62}$")) {
            throw CliException.failure("工程目录名 " + name + " 要能用作资产名（小写字母开头，小写字母、数字、连字符，至少 2 个字符），改个目录名再部署");
        }
        return name;
    }

    static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array.isArray()) for (JsonNode item : array) values.add(item.asString());
        return values;
    }

    /** 追踪里保密片段的 text 是 {$redacted, label, digest} 占位块（ADR 0004，租户视角看不到原文），转成树能用的标签文字 */
    static JsonNode redactedToLabel(JsonNode spans) {
        if (!spans.isArray()) return spans;
        for (JsonNode span : spans) {
            JsonNode segments = span.path("promptSegments");
            if (!segments.isArray()) continue;
            for (JsonNode segment : segments) {
                JsonNode text = segment.path("text");
                if (text.isObject()) {
                    ((ObjectNode) segment).put("text", "［" + text.path("label").asString("内容不可见") + "］");
                }
            }
        }
        return spans;
    }

    // ---------------------------------------------------------------- logs

    static void logs(Opts opts, PrintStream out) {
        Session session = readSession(opts);
        Api api = new Api(resolveUrl(opts, session, null), session.token());
        if (opts.opt("trace") != null) {
            JsonNode data = api.get("/traces/" + opts.opt("trace"));
            out.println(dim("Agent " + data.path("agent").asString() + "，运行 "
                    + data.path("runId").asString()));
            AgentRun.printTree(
                    out, List.of(Json.MAPPER.convertValue(redactedToLabel(data.path("spans")), SpanRecord[].class)));
            return;
        }
        int limit;
        try {
            limit = Integer.parseInt(opts.opt("limit", "20"));
        } catch (NumberFormatException e) {
            throw usage("--limit 要是 1 到 100 的数字");
        }
        String agent = opts.opt("agent");
        JsonNode runs = api.get("/runs?limit=" + limit
                + (agent == null ? "" : "&agent=" + URLEncoder.encode(agent, StandardCharsets.UTF_8)));
        if (!runs.isArray() || runs.isEmpty()) {
            out.println("还没有运行记录");
            return;
        }
        for (JsonNode run : runs) {
            String icon = switch (run.path("status").asString()) {
                case "completed" -> green("✓");
                case "failed", "rejected" -> red("✗");
                case "suspended" -> magenta("?");
                default -> dim("·");
            };
            out.println(
                    icon + " " + truncate(run.path("createdAt").asString(), 19).replace('T', ' ')
                            + dim("  " + run.path("agent").asString()) + "  "
                            + truncate(run.path("utterance").asString(), 40)
                            + dim("  追踪 " + run.path("traceId").asString()));
        }
        out.println(dim("看某次的详细追踪：pnpm eab logs --trace <追踪编号>"));
    }

    // ---------------------------------------------------------------- 公共件

    /** 命令行参数：--key value 或 --key 开关，其余按位置收进 rest */
    record Opts(LinkedHashMap<String, String> options, List<String> rest) {

        static Opts parse(String[] argv) {
            LinkedHashMap<String, String> options = new LinkedHashMap<>();
            List<String> rest = new ArrayList<>();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                if (arg.startsWith("--") && arg.length() > 2) {
                    if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) options.put(arg.substring(2), argv[++i]);
                    else options.putIfAbsent(arg.substring(2), "true");
                } else {
                    rest.add(arg);
                }
            }
            return new Opts(options, rest);
        }

        String opt(String name) {
            return options.get(name);
        }

        String opt(String name, String fallback) {
            String value = options.get(name);
            return value == null ? fallback : value;
        }

        String require(String name, String hint) {
            String value = options.get(name);
            if (value == null || value.isBlank()) {
                throw usage("缺少 --" + name + (hint == null ? "" : "：" + hint));
            }
            return value;
        }
    }

    /** 登录会话：平台地址、用户、租户与令牌，保存在仓库外的会话文件里 */
    record Session(String url, String user, String tenant, String token) {}

    static Path sessionFile(Opts opts) {
        String custom = opts.opt("session");
        return custom != null && !custom.isBlank()
                ? Path.of(custom)
                : Path.of(System.getProperty("user.home"), ".config", "eab", "cli-session.json");
    }

    static Session readSession(Opts opts) {
        Path file = sessionFile(opts);
        if (!Files.isRegularFile(file)) {
            throw CliException.failure("还没有登录，请先：pnpm eab login --user <用户>");
        }
        try {
            JsonNode node = Json.MAPPER.readTree(Files.readString(file));
            return new Session(
                    node.path("url").asString(null),
                    node.path("user").asString(null),
                    node.path("tenant").asString(null),
                    node.path("token").asString(null));
        } catch (IOException | JacksonException e) {
            throw CliException.failure("会话文件读不了（" + file + "），重新登录一次：" + e.getMessage());
        }
    }

    static void writeSession(Session session, Path file) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("url", session.url());
        node.put("user", session.user());
        node.put("tenant", session.tenant());
        node.put("token", session.token());
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, Json.MAPPER.writeValueAsString(node) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw CliException.failure("会话写不进 " + file + "：" + e.getMessage());
        }
    }

    /** 平台地址：--url > 环境变量 EAB_PLATFORM_URL > 会话 > 工程清单 > 默认本机演示环境 */
    static String resolveUrl(Opts opts, Session session, ProjectManifest manifest) {
        String url = opts.opt("url");
        if (url != null && !url.isBlank()) return trim(url);
        String fromEnv = System.getenv("EAB_PLATFORM_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return trim(fromEnv);
        if (session != null && session.url() != null && !session.url().isBlank()) return trim(session.url());
        if (manifest != null
                && manifest.platform() != null
                && !manifest.platform().isBlank()) return trim(manifest.platform());
        return "http://127.0.0.1:4000";
    }

    static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 工程模板的主资产类型；a2a 模板没有平台侧资产 */
    static String mainKindOf(String template) {
        return switch (template) {
            case "agent" -> "Agent";
            case "extension" -> "AgentExtension";
            case "skill" -> "Skill";
            default -> null;
        };
    }

    /** 工程根目录：命令要求在工程目录里执行（或 --dir 指过去） */
    static Path projectDir(Opts opts) {
        Path dir = Path.of(opts.opt("dir", "."));
        if (!Files.isRegularFile(dir.resolve(ProjectManifest.FILE))) {
            throw usage("当前目录不是 EAB 代码工程（" + dir.toAbsolutePath().normalize() + " 下没有 " + ProjectManifest.FILE
                    + "），先 pnpm eab init");
        }
        return dir;
    }

    static ProjectManifest manifest(Path dir) {
        try {
            return ProjectManifest.read(dir);
        } catch (IllegalArgumentException e) {
            throw usage(e.getMessage());
        }
    }

    static void writeManifest(Path dir, ProjectManifest manifest) {
        try {
            Files.writeString(dir.resolve(ProjectManifest.FILE), manifest.toYaml(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw CliException.failure("清单写不回 " + ProjectManifest.FILE + "：" + e.getMessage());
        }
    }

    static String readFile(Path dir, String file) {
        try {
            return Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw CliException.failure("读不到 " + file + "：" + e.getMessage());
        }
    }

    static JsonNode parseMetadata(String yaml, String file) {
        try {
            return Json.YAML.readTree(yaml);
        } catch (JacksonException e) {
            throw CliException.failure(file + " 不是合法的 YAML：" + e.getMessage());
        }
    }

    static String positional(Opts opts, int index, String hint) {
        if (opts.rest().size() <= index) throw usage(hint);
        return opts.rest().get(index);
    }

    /** 校验问题的单行呈现：消息加位置（路径:行号） */
    static String issueText(JsonNode issue) {
        StringBuilder text = new StringBuilder(issue.path("message").asString());
        String path = issue.path("path").asString(null);
        if (path != null && !path.isBlank()) text.append("（").append(path);
        if (issue.path("line").isNumber())
            text.append(":").append(issue.path("line").asString());
        if (path != null && !path.isBlank()) text.append("）");
        return text.toString();
    }

    static String truncate(String text, int max) {
        if (text == null) return "";
        String one = text.replace("\n", " ").strip();
        return one.length() <= max ? one : one.substring(0, max) + "…";
    }

    static CliException usage(String message) {
        return CliException.usage(message);
    }

    static String green(String t) {
        return AgentRun.Ansi.green(t);
    }

    static String red(String t) {
        return AgentRun.Ansi.red(t);
    }

    static String yellow(String t) {
        return AgentRun.Ansi.yellow(t);
    }

    static String magenta(String t) {
        return AgentRun.Ansi.magenta(t);
    }

    static String dim(String t) {
        return AgentRun.Ansi.dim(t);
    }

    /** 命令行错误：exit 2 是用法不对，exit 1 是操作失败；status 与 code 来自平台响应，供分支逻辑判断 */
    static final class CliException extends RuntimeException {
        private final int exit;
        private final int status;
        private final String code;

        CliException(int exit, int status, String code, String message) {
            super(message);
            this.exit = exit;
            this.status = status;
            this.code = code;
        }

        static CliException usage(String message) {
            return new CliException(2, 0, null, message);
        }

        static CliException failure(String message) {
            return new CliException(1, 0, null, message);
        }

        int exit() {
            return exit;
        }

        int status() {
            return status;
        }

        String code() {
            return code;
        }
    }

    /** 平台的 HTTP 客户端：Bearer 用户令牌，统一解开 {success, data, error} 信封 */
    static final class Api {
        private final HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        private final String base;
        private final String token;

        Api(String base, String token) {
            this.base = base;
            this.token = token;
        }

        JsonNode get(String path) {
            return call("GET", path, null);
        }

        JsonNode post(String path, Object body) {
            return call("POST", path, Json.MAPPER.writeValueAsString(body));
        }

        JsonNode put(String path, Object body) {
            return call("PUT", path, Json.MAPPER.writeValueAsString(body));
        }

        private JsonNode call(String method, String path, String body) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", "application/json");
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException e) {
                throw CliException.failure("连接不上平台（" + base + "），请先 pnpm demo");
            } catch (IOException e) {
                throw CliException.failure("请求平台失败：" + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw CliException.failure("请求平台被中断");
            }
            return unwrap(response);
        }

        /** 失败时带上 HTTP 状态码与错误码抛 CliException；issues 与 details 逐条附在消息里 */
        private JsonNode unwrap(HttpResponse<String> response) {
            JsonNode envelope;
            try {
                envelope = Json.MAPPER.readTree(response.body() == null ? "" : response.body());
            } catch (JacksonException e) {
                throw CliException.failure(
                        "平台返回的不是 JSON（HTTP " + response.statusCode() + "）：" + truncate(response.body(), 200));
            }
            if (envelope.path("success").asBoolean(false)) return envelope.path("data");
            JsonNode error = envelope.path("error");
            String code = error.path("code").asString(null);
            if (response.statusCode() == 401) {
                throw new CliException(1, 401, code, "登录已过期或未登录，请重新：pnpm eab login --user <用户>");
            }
            String message = error.path("message").asString("平台返回错误（HTTP " + response.statusCode() + "）");
            throw new CliException(1, response.statusCode(), code, message + detail(error));
        }

        /** 错误详情：issues 与 details 逐条另起一行（发布门禁、评测失败的原因靠它说清楚） */
        private static String detail(JsonNode error) {
            StringBuilder text = new StringBuilder();
            for (String field : List.of("issues", "details")) {
                JsonNode items = error.get(field);
                if (items == null || !items.isArray()) continue;
                for (JsonNode item : items) {
                    String line = item.isString()
                            ? item.asString()
                            : item.path("message").asString("");
                    if (line.isBlank()) line = Json.MAPPER.writeValueAsString(item);
                    text.append("\n  ").append(line);
                }
            }
            String code = error.path("code").asString(null);
            if (code != null) text.append("（").append(code).append("）");
            return text.toString();
        }
    }
}
