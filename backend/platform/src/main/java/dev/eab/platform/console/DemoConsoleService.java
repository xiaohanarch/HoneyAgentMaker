package dev.eab.platform.console;

import dev.eab.platform.PlatformProperties;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.cli.ResetDemo;
import dev.eab.platform.http.ApiException;
import dev.eab.platform.http.ApiResponse.ApiError;
import dev.eab.platform.hub.HistorySimulator;
import dev.eab.platform.registry.AssetSeeder;
import dev.eab.platform.runtime.RuntimeService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示控制台的演示状态操作（主提示词 17 节）：重置、跳到各故事起点、一键生成完成态。
 * 重置与 pnpm seed:reset 等价：先让 DemoERP 按今天重新生成业务数据，再清空平台的运行期数据；
 * DemoERP 不可达时返回错误、不动平台数据。故事 A–E 都从重置后的初始状态开始；故事 F 的升级回归
 * 需要故事 B 的扩展已发布，起点在重置之上再发布完成态快照（先清空该租户已发布资产，快照是唯一权威）；
 * 完成态再叠加 30 天模拟历史，治理中心与架构视图有趋势数据可看。
 */
public class DemoConsoleService {

    private final PlatformServices services;
    private final PlatformProperties props;
    private final RuntimeService runtime;
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public DemoConsoleService(PlatformServices services, PlatformProperties props, RuntimeService runtime) {
        this.services = services;
        this.props = props;
        this.runtime = runtime;
    }

    /** 重置（等价 pnpm seed:reset）：DemoERP 重新生成业务数据，平台清空运行期数据并重建演示委托 */
    public Map<String, Object> reset() {
        resetErp();
        ResetDemo.reset(services);
        return Map.of("erpReset", true, "delegation", PlatformServices.DEMO_DELEGATION_ID);
    }

    /** 跳到故事起点：A–E 为纯重置；F 再发布完成态快照（tenants/<租户> 下的扩展、技能与评测集） */
    public Map<String, Object> storyStart(String story, String tenantId) {
        if (story == null || !List.of("A", "B", "C", "D", "E", "F").contains(story)) {
            throw new ApiException(400, "BAD_REQUEST", "请求参数不合法：story 只能是 A、B、C、D、E、F");
        }
        reset();
        if (!story.equals("F")) return Map.of("story", story, "published", List.of());
        return Map.of("story", story, "published", seedSnapshot(tenantId));
    }

    /** 一键生成完成态：完成态快照 + 30 天模拟历史（带模拟标记，重置时一并清掉） */
    public Map<String, Object> complete(String tenantId) {
        reset();
        List<String> published = seedSnapshot(tenantId);
        HistorySimulator.Report[] report = new HistorySimulator.Report[1];
        services.db()
                .inTransaction(() -> report[0] = HistorySimulator.generate(
                        services.db().jdbc(),
                        tenantId,
                        LocalDate.now(services.clock().withZone(ZoneOffset.UTC))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("published", published);
        result.put("history", report[0]);
        return result;
    }

    /**
     * 发布完成态快照：快照文件是该租户完成态的唯一权威，先清空已发布资产再整树重播，
     * 演示中途发布的扩展（故事 B 的 notify_im 扩展等）不会与快照同名冲突，重演结果确定。
     * 该租户没有快照目录时不做任何事（无资产可清，也无可发布）。
     */
    private List<String> seedSnapshot(String tenantId) {
        if (!Files.isDirectory(props.resolvedAssetsDir().resolve("tenants").resolve(tenantId))) {
            return List.of();
        }
        ResetDemo.deleteTenantAssets(services, tenantId);
        AssetSeeder.Report report =
                AssetSeeder.seed(services.registry(), props.resolvedAssetsDir(), List.of("tenants/" + tenantId));
        if (!report.failed().isEmpty()) {
            throw new ApiException(
                    500,
                    new ApiError(
                            "SEED_FAILED",
                            "完成态快照发布失败",
                            report.failed().stream()
                                    .map(f -> f.file() + "：" + f.message())
                                    .toList(),
                            null));
        }
        return report.published();
    }

    /** DemoERP 侧重置走内部调用密钥端点（与事件触发同一通道），失败时平台数据不动 */
    private void resetErp() {
        try {
            var response = http.send(
                    HttpRequest.newBuilder(
                                    URI.create(runtime.settings().erpUrl()).resolve("/demo/v1/reset"))
                            .timeout(Duration.ofSeconds(30))
                            .header("x-eab-internal-token", services.internalSecret())
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 202) {
                throw new ApiException(502, "ERP_RESET_FAILED", "DemoERP 拒绝了重置：HTTP " + response.statusCode());
            }
        } catch (IOException e) {
            throw new ApiException(502, "ERP_UNREACHABLE", "无法连接 DemoERP：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "INTERRUPTED", "请求被中断");
        }
    }
}
