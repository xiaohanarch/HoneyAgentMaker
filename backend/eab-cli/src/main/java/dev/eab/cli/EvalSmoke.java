package dev.eab.cli;

import dev.eab.platform.PlatformProperties;
import dev.eab.platform.lifecycle.EvalService;
import dev.eab.platform.registry.AssetSeeder;
import dev.eab.spec.Json;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * pnpm eval:smoke [--tier full] [--tenant t001] [--verbose]
 *
 * <p>在进程内启动平台和 DemoERP（同 agent:run），加载仓库里的全部资产（包括 tenants 下的完成态快照），逐个运行该租户可见的
 * 冒烟评测集，打印每个用例的结果。冒烟集全部用例要在 3 分钟内跑完，超时或有评测集未通过时退出码非零。
 */
public final class EvalSmoke {

    private EvalSmoke() {}

    /** 冒烟集的时间预算（主提示词第 18 节） */
    static final Duration SMOKE_BUDGET = Duration.ofMinutes(3);

    record Args(String tier, String tenant, boolean verbose) {}

    static Args parse(String[] argv) {
        String tier = "smoke";
        String tenant = "t001";
        boolean verbose = false;
        for (int i = 0; i < argv.length; i++) {
            if (argv[i].equals("--tier") && i + 1 < argv.length) tier = argv[++i];
            else if (argv[i].equals("--tenant") && i + 1 < argv.length) tenant = argv[++i];
            else if (argv[i].equals("--verbose")) verbose = true;
        }
        return new Args(tier, tenant, verbose);
    }

    /** 仓库里各租户的资产子目录，平台启动时只灌入了连接 */
    static List<String> tenantDirectories(Path assetsRoot) throws java.io.IOException {
        List<String> dirs = new ArrayList<>();
        Path tenants = assetsRoot.resolve("tenants");
        if (!Files.isDirectory(tenants)) return dirs;
        try (var stream = Files.list(tenants)) {
            stream.filter(Files::isDirectory).sorted().forEach(t -> dirs.add("tenants/" + t.getFileName()));
        }
        return dirs;
    }

    static int run(Args args, PrintStream out) throws Exception {
        long start = System.currentTimeMillis();
        try (var env = new AgentRun.Environment()) {
            Path assets = env.platform.getBean(PlatformProperties.class).resolvedAssetsDir();
            var seeded = AssetSeeder.seed(env.services().registry(), assets, tenantDirectories(assets));
            seeded.failed().forEach(f -> out.println(AgentRun.Ansi.red("资产入库失败 " + f.file() + "：" + f.message())));
            EvalService evals = env.platform.getBean(EvalService.class);
            var suites = evals.suites(args.tenant(), args.tier());
            out.println(
                    AgentRun.Ansi.bold("评测集（" + args.tier() + "，租户 " + args.tenant() + "）：" + suites.size() + " 个"));
            int failedSuites = 0;
            for (var suite : suites) {
                var run = evals.run(args.tenant(), suite, "eval-cli");
                var r = run.result();
                boolean ok = r.status().equals("passed");
                if (!ok) failedSuites++;
                out.println(
                        (ok ? AgentRun.Ansi.green("✓ ") : AgentRun.Ansi.red("✗ ")) + run.suite() + " → " + run.target()
                                + AgentRun.Ansi.dim("  " + r.passed() + "/" + r.total() + "，最低通过率 " + r.minPassRate()
                                        + "，" + r.millis() + "ms"));
                for (var c : r.cases()) {
                    out.println("    " + (c.passed() ? "✓ " : AgentRun.Ansi.red("✗ ")) + c.id()
                            + AgentRun.Ansi.dim("（" + c.user() + "）"));
                    for (var a : c.assertions()) {
                        if (!a.passed()) out.println(AgentRun.Ansi.red("        " + a.type() + "：" + a.detail()));
                    }
                    if (args.verbose() && !c.passed() && c.traceId() != null) {
                        AgentRun.printTree(out, env.runtime().traces().list(c.traceId()));
                    }
                }
            }
            long millis = System.currentTimeMillis() - start;
            boolean inBudget = !args.tier().equals("smoke") || millis <= SMOKE_BUDGET.toMillis();
            out.println(AgentRun.Ansi.bold("合计用时 " + millis / 1000.0 + " 秒"
                    + (args.tier().equals("smoke") ? "（预算 " + SMOKE_BUDGET.toSeconds() + " 秒）" : "")));
            if (!inBudget) out.println(AgentRun.Ansi.red("冒烟集超出 3 分钟的时间预算"));
            return failedSuites == 0 && inBudget ? 0 : 1;
        }
    }

    public static void main(String[] argv) throws Exception {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        String fromEnv = System.getenv("EAB_CLI_ARGS");
        if (fromEnv != null && !fromEnv.isBlank()) argv = Json.MAPPER.readValue(fromEnv, String[].class);
        System.exit(run(parse(argv), out));
    }
}
