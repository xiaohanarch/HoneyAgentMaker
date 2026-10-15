package dev.eab.erp.bo;

import dev.eab.erp.DemoErpApplication;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DemoERP 的 OpenAPI 描述。BO API 由路由声明生成：operationId 与工具名一致，
 * 每个操作用扩展字段 x-write-level 标出写操作级别（主提示词 6.3 节）。
 */
public final class OpenApiDocs {

    private OpenApiDocs() {}

    private record Other(String method, String path, String tag, String summary) {}

    /** 不属于 BO API 的接口，只出现在 /openapi.json */
    private static final List<Other> OTHERS = List.of(
            new Other("get", "/open/v1/suppliers", "open", "集成接口：供应商（不做数据权限过滤）"),
            new Other("get", "/open/v1/purchase-orders", "open", "集成接口：采购订单（不做数据权限过滤）"),
            new Other("get", "/meta/v1/objects", "meta", "业务对象清单"),
            new Other("get", "/meta/v1/objects/{ref}", "meta", "单个对象的定义，例如 erp.pur.Supplier@v1"),
            new Other("get", "/meta/v1/rules", "meta", "业务规则清单"),
            new Other("get", "/meta/v1/permission-dimensions", "meta", "数据权限维度"),
            new Other("post", "/demo/v1/events/trigger", "demo", "演示控制台：触发一个业务事件"));

    public static Map<String, Object> document(List<BoRoute> routes, boolean onlyBo) {
        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        for (BoRoute route : routes) {
            paths.computeIfAbsent(route.path(), p -> new LinkedHashMap<>())
                    .put(route.method().toLowerCase(), operation(route));
        }
        if (!onlyBo) {
            for (Other other : OTHERS) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("tags", List.of(other.tag()));
                op.put("summary", other.summary());
                op.put("responses", Map.of("200", Map.of("description", "成功")));
                paths.computeIfAbsent(other.path(), p -> new LinkedHashMap<>()).put(other.method(), op);
            }
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "DemoERP");
        info.put("version", DemoErpApplication.SERVICE_VERSION);
        info.put("description", "BO API 只接受委托令牌（用户 + Agent），按业务权限和数据权限裁决；Open API 只接受集成账号，只做租户隔离。");
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("openapi", "3.0.3");
        doc.put("info", info);
        doc.put("paths", paths);
        return doc;
    }

    private static Map<String, Object> operation(BoRoute route) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", route.operation());
        op.put("tags", List.of("bo"));
        op.put("summary", route.summary());
        op.put("description", route.description());
        List<Map<String, Object>> parameters = new java.util.ArrayList<>();
        route.params().forEach(p -> parameters.add(parameter(p, "path")));
        route.query().forEach(p -> parameters.add(parameter(p, "query")));
        if (!parameters.isEmpty()) op.put("parameters", parameters);
        if (route.body() != null) {
            Map<String, Object> properties = new LinkedHashMap<>();
            route.body().forEach(p -> properties.put(p.name(), p.schema()));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            List<String> required = route.body().stream()
                    .filter(Param::required)
                    .map(Param::name)
                    .toList();
            if (!required.isEmpty()) schema.put("required", required);
            op.put(
                    "requestBody",
                    Map.of("required", true, "content", Map.of("application/json", Map.of("schema", schema))));
        }
        op.put("responses", Map.of("200", Map.of("description", "成功")));
        op.put("x-write-level", BoTypes.OPERATIONS.get(route.operation()).writeLevel());
        return op;
    }

    private static Map<String, Object> parameter(Param param, String in) {
        Map<String, Object> schema = new LinkedHashMap<>(param.schema());
        Object description = schema.remove("description");
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("name", param.name());
        parameter.put("in", in);
        parameter.put("required", param.required() || in.equals("path"));
        if (description != null) parameter.put("description", description);
        parameter.put("schema", schema);
        return parameter;
    }
}
