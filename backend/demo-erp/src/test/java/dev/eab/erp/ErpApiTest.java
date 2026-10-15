package dev.eab.erp;

import static dev.eab.erp.TestErp.INTERNAL_SECRET;
import static dev.eab.erp.TestErp.bearer;
import static dev.eab.erp.TestErp.clientToken;
import static dev.eab.erp.TestErp.delegatedToken;
import static dev.eab.erp.TestErp.pageToken;
import static dev.eab.erp.TestErp.userToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import dev.eab.directory.DemoDirectory;
import dev.eab.erp.TestErp.Delegation;
import dev.eab.erp.link.LocalPlatformLink.Published;
import dev.eab.erp.meta.MetaCatalog;
import dev.eab.spec.AssetLoader;
import dev.eab.spec.AssetParser;
import dev.eab.spec.model.Asset;
import dev.eab.spec.testing.RepoPaths;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

/** 对应 TS 版 apps/demo-erp/src/app.test.ts，断言逐条翻译 */
class ErpApiTest {

    static final List<String> PO_OPS = List.of(
            "purchase_order_list",
            "purchase_order_change_delivery_date",
            "purchase_order_change_approve",
            "supplier_get");

    TestErp erp;

    @BeforeEach
    void setUp() {
        erp = TestErp.create();
    }

    @AfterEach
    void tearDown() {
        erp.close();
    }

    MockHttpServletResponse send(RequestBuilder request) throws Exception {
        MockHttpServletResponse response = erp.mvc.perform(request).andReturn().getResponse();
        response.setCharacterEncoding("UTF-8");
        return response;
    }

    MockHttpServletResponse get(String url, String token) throws Exception {
        return send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)
                .header("authorization", bearer(token)));
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return ErpJson.MAPPER.readTree(response.getContentAsString());
    }

    MockHttpServletResponse postJson(String url, String token, String body, String idempotencyKey) throws Exception {
        var request = post(url).header("authorization", bearer(token));
        if (idempotencyKey != null) request = request.header("idempotency-key", idempotencyKey);
        if (body != null)
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        return send(request);
    }

    static Set<String> texts(JsonNode items, String field) {
        Set<String> values = new HashSet<>();
        items.forEach(item -> values.add(item.path(field).asString()));
        return values;
    }

    List<Map<String, Object>> boAccess() {
        return erp.link.published().stream()
                .filter(e -> e.type().equals("bo.access"))
                .map(Published::payload)
                .toList();
    }

    Published lastPublished() {
        return erp.link.published().getLast();
    }

    @Nested
    @DisplayName("数据权限：同一个问题，不同用户结果不同（故事 D 第 1 步）")
    class DataScope {

        @Test
        @DisplayName("张敏只看到华东，李强只看到华南，结果都注明过滤范围")
        void scopedByPurchasingOrg() throws Exception {
            String url = "/bo/v1/purchase-orders?overdueDaysGt=30";
            JsonNode zhang = json(get(url, delegatedToken("zhangmin", PO_OPS)));
            JsonNode li = json(get(url, delegatedToken("liqiang", PO_OPS)));

            assertThat(zhang.path("filtered").asBoolean()).isTrue();
            assertThat(zhang.path("visibleScope").toString()).isEqualTo("[\"华东\"]");
            assertThat(zhang.path("scopeLabel").asString()).isEqualTo("采购组织：华东");
            assertThat(li.path("filtered").asBoolean()).isTrue();
            assertThat(li.path("visibleScope").toString()).isEqualTo("[\"华南\"]");
            assertThat(li.path("scopeLabel").asString()).isEqualTo("采购组织：华南");
            assertThat(texts(zhang.path("items"), "purchasingOrgName")).containsExactly("华东");
            assertThat(texts(li.path("items"), "purchasingOrgName")).containsExactly("华南");
            JsonNode po = null;
            for (JsonNode item : zhang.path("items"))
                if (item.path("id").asString().equals("PO-2026-0117")) po = item;
            assertThat(po).isNotNull();
            assertThat(po.path("supplierName").asString()).isEqualTo("百润精密");
            assertThat(po.path("overdueDays").asInt()).isEqualTo(120);
        }

        @Test
        @DisplayName("不在数据权限内的单据返回 404，不泄露是否存在")
        void outOfScopeIsNotFound() throws Exception {
            var response = get("/bo/v1/suppliers/S-0001", delegatedToken("liqiang", PO_OPS));
            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(json(response).path("error").path("suggestion").asString())
                    .isNotEmpty();
        }

        @Test
        @DisplayName("租户隔离：t002 的用户查不到 t001 的供应商")
        void tenantIsolation() throws Exception {
            var response = get(
                    "/bo/v1/suppliers?name=百润",
                    delegatedToken(new Delegation("zhouting", List.of("supplier_list")).tenant("t002")));
            assertThat(json(response).path("items").size()).isZero();
        }
    }

    @Nested
    @DisplayName("Open API 对照（故事 D 第 2 步）")
    class OpenApi {

        @Test
        @DisplayName("集成账号拿到两个采购组织的全部数据，没有过滤")
        void clientSeesEverything() throws Exception {
            JsonNode response = json(get("/open/v1/purchase-orders?overdueDaysGt=30", clientToken("t001")));
            assertThat(response.path("filtered").asBoolean()).isFalse();
            assertThat(texts(response.path("items"), "purchasingOrgName")).containsExactlyInAnyOrder("华东", "华南");
        }

        @Test
        @DisplayName("集成账号调用 BO API 被拒绝：没有人的身份")
        void clientCannotCallBo() throws Exception {
            var response = get("/bo/v1/purchase-orders", clientToken("t001"));
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("AGENT_IDENTITY_REQUIRED");
        }

        @Test
        @DisplayName("委托令牌不能调用 Open API")
        void delegatedCannotCallOpen() throws Exception {
            assertThat(get("/open/v1/suppliers", delegatedToken("zhangmin", PO_OPS))
                            .getStatus())
                    .isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("鉴权点 4 的各项检查")
    class Checkpoint4 {

        @Test
        @DisplayName("没有 Agent 身份的用户令牌被拒绝")
        void userTokenRejected() throws Exception {
            var response = get("/bo/v1/purchase-orders", userToken("zhangmin"));
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("AGENT_IDENTITY_REQUIRED");
        }

        @Test
        @DisplayName("缺少令牌和伪造的令牌被拒绝")
        void missingOrForgedToken() throws Exception {
            var missing =
                    send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/bo/v1/suppliers"));
            assertThat(missing.getStatus()).isEqualTo(401);
            assertThat(json(missing).path("error").path("code").asString()).isEqualTo("AUTH_REQUIRED");
            var forged = get("/bo/v1/suppliers", "not-a-jwt");
            assertThat(json(forged).path("error").path("code").asString()).isEqualTo("INVALID_TOKEN");
        }

        @Test
        @DisplayName("用户在 ERP 里没有的权限，通过 Agent 也没有")
        void businessPermission() throws Exception {
            var response = postJson(
                    "/bo/v1/delivery-date-changes/DC-2026-0001/approve", delegatedToken("liqiang", PO_OPS), null, null);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("BUSINESS_PERMISSION_DENIED");
        }

        @Test
        @DisplayName("Agent 没有声明的操作被拒绝")
        void agentScope() throws Exception {
            var response = get("/bo/v1/suppliers", delegatedToken("zhangmin", List.of("purchase_order_list")));
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("AGENT_SCOPE_DENIED");
        }

        @Test
        @DisplayName("令牌里的租户与用户不符时被拒绝")
        void tenantMismatch() throws Exception {
            var response = get(
                    "/bo/v1/suppliers",
                    delegatedToken(new Delegation("zhangmin", List.of("supplier_list")).tenant("t002")));
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("TENANT_MISMATCH");
        }

        @Test
        @DisplayName("受托运行时委托必须有效，撤销后被拒绝")
        void delegationMustBeActive() throws Exception {
            String token = delegatedToken(new Delegation("sunkg", List.of("inventory_balance_list"))
                    .agent("vendor.scm/inventory-risk-analyst")
                    .delegated("DLG-0001"));
            assertThat(json(get("/bo/v1/inventory-balances", token))
                            .path("error")
                            .path("code")
                            .asString())
                    .isEqualTo("DELEGATION_REVOKED");
            erp.link.activeDelegations().add("DLG-0001");
            JsonNode ok = json(get("/bo/v1/inventory-balances?belowSafetyStock=true", token));
            assertThat(texts(ok.path("items"), "materialId")).contains("M-0007");
            assertThat(boAccess().getLast()).containsEntry("delegationId", "DLG-0001");
        }

        @Test
        @DisplayName("每次裁决都推送到平台审计，带用户、Agent、数据范围")
        void everyDecisionIsAudited() throws Exception {
            get("/bo/v1/purchase-orders", delegatedToken("zhangmin", PO_OPS));
            get("/bo/v1/suppliers", delegatedToken("zhangmin", List.of("purchase_order_list")));
            var events = boAccess();
            assertThat(events).hasSize(2);
            assertThat(events.get(0))
                    .containsEntry("userId", "zhangmin")
                    .containsEntry("agent", "vendor.pur/supplier-performance-analyst@1.0.0")
                    .containsEntry("outcome", "allow")
                    .containsEntry("dataScope", "采购组织：华东");
            assertThat(events.get(1)).containsEntry("outcome", "deny").containsEntry("code", "AGENT_SCOPE_DENIED");
            long logged = erp.services
                    .db()
                    .jdbc()
                    .sql("SELECT COUNT(*) FROM access_log")
                    .query(Long.class)
                    .single();
            assertThat(logged).isEqualTo(2);
        }

        @Test
        @DisplayName("参数不合法时返回结构化的 400")
        void invalidParameters() throws Exception {
            var response = get("/bo/v1/purchase-orders?limit=0", delegatedToken("zhangmin", PO_OPS));
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("BAD_REQUEST");
            var badEnum = get("/bo/v1/suppliers?rating=D", delegatedToken("zhangmin", List.of("supplier_list")));
            assertThat(badEnum.getStatus()).isEqualTo(400);
            var noBody = postJson(
                    "/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes",
                    delegatedToken("zhangmin", PO_OPS),
                    "{\"newDate\":\"2026/11/15\"}",
                    null);
            assertThat(noBody.getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("没有的接口返回 404")
        void unknownRoute() throws Exception {
            var response = get("/bo/v1/unknown", delegatedToken("zhangmin", PO_OPS));
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("交期变更与职责分离（故事 D 第 3 步）")
    class DeliveryDateChange {

        static final String SUBMIT = "/bo/v1/purchase-orders/PO-2026-0117/delivery-date-changes";

        @Test
        @DisplayName("张敏提交变更后不能审批自己的变更单；幂等键重放返回同一张变更单")
        void sodAndIdempotency() throws Exception {
            String token = delegatedToken("zhangmin", PO_OPS);
            String body = "{\"newDate\":\"2026-11-15\",\"reason\":\"供应商产能不足\"}";
            var first = postJson(SUBMIT, token, body, "chg-1");
            assertThat(first.getStatus()).isEqualTo(201);
            JsonNode item = json(first).path("item");
            assertThat(item.path("id").asString()).isEqualTo("DC-2026-0001");
            assertThat(item.path("oldDate").asString()).isEqualTo("2026-06-17");
            assertThat(item.path("status").asString()).isEqualTo("submitted");
            assertThat(item.path("requestedBy").asString()).isEqualTo("zhangmin");
            assertThat(item.path("requestedVia").asString()).isEqualTo("agent:vendor.pur/supplier-performance-analyst");

            var replay = postJson(SUBMIT, token, body, "chg-1");
            assertThat(replay.getHeader("idempotent-replayed")).isEqualTo("true");
            assertThat(replay.getStatus()).isEqualTo(201);
            assertThat(json(replay).path("item").path("id").asString()).isEqualTo("DC-2026-0001");

            var approve = postJson("/bo/v1/delivery-date-changes/DC-2026-0001/approve", token, null, null);
            assertThat(approve.getStatus()).isEqualTo(403);
            assertThat(json(approve).path("error").path("code").asString()).isEqualTo("SOD_VIOLATION");
            assertThat(json(approve).path("error").path("reason").asString()).contains("职责分离");
        }

        @Test
        @DisplayName("新交期必须晚于今天，错误是结构化的")
        void newDateMustBeInFuture() throws Exception {
            var response = postJson(
                    SUBMIT, delegatedToken("zhangmin", PO_OPS), "{\"newDate\":\"2026-01-01\",\"reason\":\"x\"}", null);
            assertThat(response.getStatus()).isEqualTo(422);
            List<String> keys = json(response).path("error").propertyNames().stream()
                    .sorted()
                    .toList();
            assertThat(keys).containsExactly("code", "reason", "suggestion");
        }

        @Test
        @DisplayName("另一个有审批权限的用户可以审批，订单交期随之改写")
        void otherUserCanApprove() throws Exception {
            postJson(SUBMIT, delegatedToken("zhangmin", PO_OPS), "{\"newDate\":\"2026-11-15\",\"reason\":\"x\"}", null);
            // 孙库管没有审批权限；这里改用直接写库的方式模拟另一位采购经理提交
            erp.services
                    .db()
                    .jdbc()
                    .sql("UPDATE delivery_date_changes SET requested_by = 'someone-else' WHERE id = 'DC-2026-0001'")
                    .update();
            var approve = postJson(
                    "/bo/v1/delivery-date-changes/DC-2026-0001/approve",
                    delegatedToken("zhangmin", PO_OPS),
                    null,
                    null);
            assertThat(approve.getStatus()).isEqualTo(200);
            assertThat(json(approve).path("item").path("status").asString()).isEqualTo("approved");
            String planned = erp.services
                    .db()
                    .jdbc()
                    .sql(
                            "SELECT planned_delivery_date FROM po_lines WHERE tenant_id = 't001' AND po_id = 'PO-2026-0117'")
                    .query(String.class)
                    .list()
                    .getFirst();
            assertThat(planned).isEqualTo("2026-11-15");
            var again = postJson(
                    "/bo/v1/delivery-date-changes/DC-2026-0001/approve",
                    delegatedToken("zhangmin", PO_OPS),
                    null,
                    null);
            assertThat(json(again).path("error").path("code").asString()).isEqualTo("INVALID_STATE");
        }
    }

    @Nested
    @DisplayName("财务与库存")
    class FinanceAndInventory {

        @Test
        @DisplayName("王芳看到本月金额超过 50 万的高风险异常（故事 B）")
        void highRiskAnomalies() throws Exception {
            var response = json(get(
                    "/bo/v1/transaction-anomalies?severity=high,critical&period=2026-10",
                    delegatedToken(new Delegation("wangfang", List.of("transaction_anomaly_list"))
                            .agent("vendor.fin/transaction-anomaly-analyst"))));
            long big = 0;
            for (JsonNode a : response.path("items")) if (a.path("amount").asDouble() > 500_000) big++;
            assertThat(big).isGreaterThanOrEqualTo(2);
            assertThat(response.path("scopeLabel").asString()).isEqualTo("公司主体：星海制造本部");
            assertThat(response.path("items").get(0).path("invoiceIds").isArray())
                    .isTrue();
        }

        @Test
        @DisplayName("单条异常附关联发票")
        void anomalyDetail() throws Exception {
            var response = json(get(
                    "/bo/v1/transaction-anomalies/ANO-2026-0003",
                    delegatedToken(new Delegation("wangfang", List.of("transaction_anomaly_get"))
                            .agent("vendor.fin/transaction-anomaly-analyst"))));
            assertThat(response.path("item").path("id").asString()).isEqualTo("ANO-2026-0003");
            assertThat(response.path("item").path("invoices").size())
                    .isEqualTo(response.path("item").path("invoiceIds").size());
        }

        @Test
        @DisplayName("资产核算本期结账检查有 3 项未通过")
        void closeChecks() throws Exception {
            JsonNode body = json(get(
                    "/bo/v1/accounting-periods/current/close-checks",
                    delegatedToken("wangfang", List.of("period_close_checklist"))));
            long failed = 0;
            for (JsonNode c : body.path("items"))
                if (c.path("status").asString().equals("failed")) failed++;
            assertThat(failed).isEqualTo(3);
            assertThat(body.path("readinessRate").asDouble()).isEqualTo(50);
        }

        @Test
        @DisplayName("创建跟进任务，记录经由的 Agent")
        void followupTask() throws Exception {
            var response = postJson(
                    "/bo/v1/followup-tasks",
                    delegatedToken(new Delegation("wangfang", List.of("followup_task_create"))
                            .agent("vendor.fin/transaction-anomaly-analyst")),
                    "{\"title\":\"核查重复付款\",\"assignee\":\"应付组\",\"relatedObject\":\"ANO-2026-0003\"}",
                    null);
            JsonNode item = json(response).path("item");
            assertThat(item.path("id").asString()).isEqualTo("TASK-2026-0001");
            assertThat(item.path("createdBy").asString()).isEqualTo("wangfang");
            assertThat(item.path("createdVia").asString()).isEqualTo("agent:vendor.fin/transaction-anomaly-analyst");
        }

        @Test
        @DisplayName("其余只读查询按数据权限返回")
        void otherReads() throws Exception {
            String sun = delegatedToken(
                    new Delegation("sunkg", List.of("material_list", "stock_movement_list", "delivery_list"))
                            .agent("vendor.scm/inventory-risk-analyst"));
            assertThat(json(get("/bo/v1/materials?id=M-0007", sun))
                            .path("items")
                            .size())
                    .isEqualTo(1);
            assertThat(json(get("/bo/v1/stock-movements?limit=5", sun))
                            .path("items")
                            .size())
                    .isEqualTo(5);
            JsonNode ratedA =
                    json(get("/bo/v1/suppliers?rating=A", delegatedToken("zhangmin", List.of("supplier_list"))));
            assertThat(ratedA.path("filtered").asBoolean()).isTrue();
            assertThat(texts(ratedA.path("items"), "rating")).containsExactly("A");
            JsonNode rfqs = json(get("/bo/v1/rfqs?supplierId=S-0001", delegatedToken("zhangmin", List.of("rfq_list"))));
            assertThat(rfqs.path("items").get(0).path("id").asString()).isEqualTo("RFQ-2026-0058");
            assertThat(rfqs.path("items").get(0).path("quotationLines").size()).isEqualTo(3);
            JsonNode deliveries = json(get("/bo/v1/deliveries?from=2026-01-01&limit=3", sun));
            assertThat(deliveries.path("items").size()).isEqualTo(3);
            String fang = delegatedToken(new Delegation("wangfang", List.of("ap_invoice_list", "payment_plan_list"))
                    .agent("vendor.fin/transaction-anomaly-analyst"));
            assertThat(json(get("/bo/v1/ap-invoices?limit=2", fang))
                            .path("items")
                            .size())
                    .isEqualTo(2);
            assertThat(json(get("/bo/v1/payment-plans?status=planned", fang))
                            .path("scopeLabel")
                            .asString())
                    .isEqualTo("公司主体：星海制造本部");
        }
    }

    @Nested
    @DisplayName("接口描述与元数据")
    class Descriptions {

        @Test
        @DisplayName("BO API 的 OpenAPI 操作与工具来源的工具一一对应，写级别一致")
        void openApiMatchesToolSource() throws Exception {
            JsonNode doc = json(send(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/bo/v1/openapi.json")));
            Map<String, String> levels = new java.util.HashMap<>();
            doc.path("paths")
                    .forEach(methods -> methods.forEach(op -> levels.put(
                            op.path("operationId").asString(),
                            op.path("x-write-level").asString())));
            assertThat(levels.keySet()).containsExactlyInAnyOrderElementsOf(DemoDirectory.BO_OPERATIONS);

            var source =
                    AssetParser.parseYaml(Files.readString(RepoPaths.assets().resolve("vendor/erp/demo-erp-bo.yaml")));
            assertThat(source.ok()).isTrue();
            var tools = ((Asset.ToolSourceAsset) source.asset()).spec().tools();
            for (var tool : tools) {
                assertThat(levels.get(tool.name())).as(tool.name()).isEqualTo(tool.writeLevel());
            }
            JsonNode all = json(
                    send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/openapi.json")));
            assertThat(all.path("openapi").asString()).startsWith("3.");
            assertThat(all.path("paths").has("/open/v1/suppliers")).isTrue();
        }

        @Test
        @DisplayName("三个语义包投影段引用的元数据对象都能在元数据服务里找到")
        void semanticPackRefsResolve() throws Exception {
            var loaded = AssetLoader.loadDirectory(RepoPaths.assets().resolve("vendor"));
            List<String> refs = loaded.assets().stream()
                    .flatMap(a -> a.asset() instanceof Asset.SemanticPackAsset pack
                            ? pack.spec().objects().stream().map(o -> o.ref())
                            : java.util.stream.Stream.empty())
                    .toList();
            assertThat(refs.size()).isGreaterThanOrEqualTo(15);
            assertThat(refs.stream().filter(ref -> MetaCatalog.find(ref).isEmpty()))
                    .isEmpty();
            JsonNode response = json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/meta/v1/objects/erp.pur.PurchaseOrder@v2")));
            assertThat(response.path("ref").asString()).isEqualTo("erp.pur.PurchaseOrder@v2");
            assertThat(response.path("permissionDimension").asString()).isEqualTo("采购组织");
            var missing = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/meta/v1/objects/erp.pur.PurchaseOrder@v9"));
            assertThat(missing.getStatus()).isEqualTo(404);
            assertThat(json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/meta/v1/permission-dimensions")))
                            .path("items")
                            .size())
                    .isEqualTo(2);
            assertThat(json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/meta/v1/rules")))
                            .path("items")
                            .size())
                    .isEqualTo(3);
            assertThat(json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/meta/v1/objects")))
                            .path("items")
                            .size())
                    .isEqualTo(MetaCatalog.OBJECTS.size());
        }
    }

    @Nested
    @DisplayName("演示控制台与只读页面")
    class ConsoleAndPages {

        @Test
        @DisplayName("触发业务事件需要内部调用密钥，事件推送给平台")
        void triggerEvent() throws Exception {
            var denied = send(post("/demo/v1/events/trigger")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"delivery.overdue\"}"));
            assertThat(denied.getStatus()).isEqualTo(401);
            var ok = send(post("/demo/v1/events/trigger")
                    .header("x-eab-internal-token", INTERNAL_SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"delivery.overdue\"}"));
            assertThat(ok.getStatus()).isEqualTo(202);
            Published last = lastPublished();
            assertThat(last.type()).isEqualTo("delivery.overdue");
            assertThat(last.payload()).containsEntry("poId", "PO-2026-0117").containsEntry("overdueDays", 120);
            var unknown = send(post("/demo/v1/events/trigger")
                    .header("x-eab-internal-token", INTERNAL_SECRET)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"nope\"}"));
            assertThat(unknown.getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("只读页面需要平台签发的页面令牌，并按该用户的数据权限展示")
        void pages() throws Exception {
            var anonymous = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/pages/purchase-orders/PO-2026-0117?tenant=t001"));
            assertThat(anonymous.getStatus()).isEqualTo(404);
            assertThat(anonymous.getContentAsString()).doesNotContain("百润精密");

            String zhang = pageToken("zhangmin", "t001");
            var supplier = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/pages/suppliers/S-0001?t=" + zhang));
            assertThat(supplier.getContentAsString()).contains("百润精密");
            var po = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/pages/purchase-orders/PO-2026-0117?t=" + zhang));
            assertThat(po.getContentAsString()).contains("120");
            assertThat(po.getHeader("cache-control")).isEqualTo("no-store");

            // 李强的数据权限是华南，看不到华东的单据；t002 的用户看不到 t001 的单据
            var li = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/pages/purchase-orders/PO-2026-0117?t=" + pageToken("liqiang", "t001")));
            assertThat(li.getStatus()).isEqualTo(404);
            var other = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/pages/suppliers/S-0001?t=" + pageToken("zhouting", "t002")));
            assertThat(other.getContentAsString()).doesNotContain("百润精密");
            // 委托令牌不能当页面令牌用
            var wrong = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/pages/suppliers/S-0001?t=" + delegatedToken("zhangmin", PO_OPS)));
            assertThat(wrong.getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("页面转义 HTML 特殊字符")
        void escapesHtml() {
            assertThat(dev.eab.erp.pages.PageController.escape("<a href=\"x\">'&'</a>"))
                    .isEqualTo("&lt;a href=&quot;x&quot;&gt;&#39;&amp;&#39;&lt;/a&gt;");
        }

        @Test
        @DisplayName("健康检查")
        void health() throws Exception {
            JsonNode body =
                    json(send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/health")));
            assertThat(body.path("status").asString()).isEqualTo("ok");
            assertThat(body.path("service").asString()).isEqualTo("demo-erp");
        }
    }

    @Nested
    @DisplayName("业务处理中的拒绝也进入平台审计")
    class DenialsAudited {

        @Test
        @DisplayName("职责分离被拒绝时推送 bo.access 拒绝事件")
        void sodDenialAudited() throws Exception {
            String token = delegatedToken("zhangmin", PO_OPS);
            postJson(DeliveryDateChange.SUBMIT, token, "{\"newDate\":\"2026-11-15\",\"reason\":\"x\"}", null);
            postJson("/bo/v1/delivery-date-changes/DC-2026-0001/approve", token, null, null);
            Published last = lastPublished();
            assertThat(last.type()).isEqualTo("bo.access");
            assertThat(last.tenantId()).isEqualTo("t001");
            assertThat(last.payload())
                    .containsEntry("outcome", "deny")
                    .containsEntry("code", "SOD_VIOLATION")
                    .containsEntry("userId", "zhangmin");
        }
    }

    @Nested
    @DisplayName("安全审查的回归测试")
    class SecurityRegressions {

        @Test
        @DisplayName("幂等键被另一个用户复用时拒绝，不回放别人的结果")
        void keyReusedByOtherUser() throws Exception {
            String body = "{\"newDate\":\"2026-11-15\",\"reason\":\"张敏提交\"}";
            var first = postJson(DeliveryDateChange.SUBMIT, delegatedToken("zhangmin", PO_OPS), body, "shared-key");
            assertThat(first.getStatus()).isEqualTo(201);
            var reuse = postJson(DeliveryDateChange.SUBMIT, delegatedToken("liqiang", PO_OPS), body, "shared-key");
            assertThat(reuse.getStatus()).isEqualTo(422);
            assertThat(json(reuse).path("error").path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            assertThat(reuse.getContentAsString()).doesNotContain("DC-2026-0001");
        }

        @Test
        @DisplayName("同一个用户用同一个键提交不同内容时拒绝")
        void keyReusedWithDifferentBody() throws Exception {
            String token = delegatedToken("zhangmin", PO_OPS);
            assertThat(postJson(DeliveryDateChange.SUBMIT, token, "{\"newDate\":\"2026-11-15\",\"reason\":\"x\"}", "k2")
                            .getStatus())
                    .isEqualTo(201);
            var second =
                    postJson(DeliveryDateChange.SUBMIT, token, "{\"newDate\":\"2026-12-01\",\"reason\":\"x\"}", "k2");
            assertThat(json(second).path("error").path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        }

        @Test
        @DisplayName("幂等键过长时拒绝")
        void keyTooLong() throws Exception {
            var response = postJson(
                    DeliveryDateChange.SUBMIT,
                    delegatedToken("zhangmin", PO_OPS),
                    "{\"newDate\":\"2026-11-15\",\"reason\":\"x\"}",
                    "k".repeat(129));
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_TOO_LONG");
        }

        @Test
        @DisplayName("受托自主运行不能执行写操作，即使令牌里带了这个操作")
        void delegatedWriteForbidden() throws Exception {
            erp.link.activeDelegations().add("DLG-X");
            String token = delegatedToken(new Delegation("sunkg", List.of("followup_task_create"))
                    .agent("vendor.scm/inventory-risk-analyst")
                    .delegated("DLG-X"));
            var response = postJson("/bo/v1/followup-tasks", token, "{\"title\":\"t\",\"assignee\":\"a\"}", null);
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(json(response).path("error").path("code").asString()).isEqualTo("DELEGATED_WRITE_FORBIDDEN");
        }

        @Test
        @DisplayName("同一张订单已有待审批的变更单时，不能再提交")
        void pendingChangeBlocksNewOne() throws Exception {
            String token = delegatedToken("zhangmin", PO_OPS);
            String body = "{\"newDate\":\"2026-11-15\",\"reason\":\"x\"}";
            assertThat(postJson(DeliveryDateChange.SUBMIT, token, body, null).getStatus())
                    .isEqualTo(201);
            var second = postJson(DeliveryDateChange.SUBMIT, token, body, null);
            assertThat(second.getStatus()).isEqualTo(409);
            assertThat(json(second).path("error").path("code").asString()).isEqualTo("CHANGE_PENDING");
        }

        @Test
        @DisplayName("审计里的目标不带查询字符串")
        void auditTargetHasNoQuery() throws Exception {
            get("/bo/v1/suppliers?name=secret", delegatedToken("zhangmin", List.of("purchase_order_list")));
            assertThat(lastPublished().payload()).containsEntry("target", "/bo/v1/suppliers");
        }
    }
}
