package dev.eab.platform.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.erp.bo.InventoryFinanceRoutes;
import dev.eab.erp.bo.OpenApiDocs;
import dev.eab.erp.bo.PurchasingRoutes;
import dev.eab.spec.AssetParser;
import dev.eab.spec.Json;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.testing.RepoPaths;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * BO 工具来源与 DemoERP 的 OpenAPI 保持一致。修改 BO 操作的参数后运行
 * mvnw -pl platform test -Dtest=BoToolSourceSyncTest -Deab.updateBoTools=true 写回资产（或在演示环境运行 pnpm tools:import-bo --write）。
 */
class BoToolSourceSyncTest {

    static JsonNode openApi() {
        var routes = new ArrayList<>(new PurchasingRoutes(null, Clock.systemUTC()).routes());
        routes.addAll(new InventoryFinanceRoutes(null, Clock.systemUTC()).routes());
        return Json.MAPPER.valueToTree(OpenApiDocs.document(routes, true));
    }

    @Test
    @DisplayName("资产里的 BO 工具与 OpenAPI 一一对应，写级别、方法、路径、入参 schema 都一致")
    void inSync() throws Exception {
        var out = new ByteArrayOutputStream();
        boolean write = Boolean.getBoolean("eab.updateBoTools");
        int code = ImportBoTools.run(openApi(), RepoPaths.assets(), write, new PrintStream(out, true, StandardCharsets.UTF_8));
        assertThat(code).as(out.toString(StandardCharsets.UTF_8)).isZero();
        var asset = (ToolSourceAsset) AssetParser.parseYaml(Files.readString(RepoPaths.assets().resolve(ImportBoTools.ASSET)))
                .asset();
        assertThat(ImportBoTools.compare(asset, ImportBoTools.fromOpenApi(openApi())).clean()).isTrue();
    }

    @Test
    @DisplayName("生成的入参 schema 含路径参数、查询参数和请求体字段，必填项合并")
    void generatesSchemas() {
        var tools = ImportBoTools.fromOpenApi(openApi());
        var change = tools.get("purchase_order_change_delivery_date");
        assertThat(change.method()).isEqualTo("POST");
        assertThat(change.writeLevel()).isEqualTo("L2");
        assertThat(change.inputSchema().path("properties").has("poId")).isTrue();
        assertThat(change.inputSchema().path("properties").has("newDate")).isTrue();
        assertThat(change.inputSchema().path("required").toString()).contains("poId", "newDate", "reason");
        assertThat(tools.get("supplier_list").inputSchema().path("properties").path("limit").path("maximum").asInt())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("写回时保留注释和描述，只插入 inputSchema 并升次版本号")
    void rewritePreservesText() {
        String yaml = "metadata:\n  version: 1.1.0\nspec:\n  tools:\n    # ── 采购\n    - name: supplier_list\n"
                + "      description: 人工写的描述\n      operation: { method: GET, path: /bo/v1/suppliers }\n"
                + "      inputSchema: {\"type\":\"object\"}\n";
        String rewritten = ImportBoTools.rewrite(yaml, ImportBoTools.fromOpenApi(openApi()));
        assertThat(rewritten).contains("version: 1.2.0", "# ── 采购", "人工写的描述", "\"maximum\":200");
        assertThat(rewritten.lines().filter(l -> l.strip().startsWith("inputSchema:"))).hasSize(1);
    }
}
