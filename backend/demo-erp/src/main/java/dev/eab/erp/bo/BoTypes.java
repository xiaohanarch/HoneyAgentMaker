package dev.eab.erp.bo;

import dev.eab.directory.DemoDirectory.DemoUser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** BO API 的基础类型：操作的数据权限维度与写级别、调用上下文、结构化错误 */
public final class BoTypes {

    private BoTypes() {}

    /** 数据权限维度：采购域按采购组织过滤，库存与财务按公司主体过滤，物料主数据和跟进任务只做租户隔离 */
    public enum Dimension {
        purchasingOrg("采购组织"),
        company("公司主体"),
        none("租户");

        final String label;

        Dimension(String label) {
            this.label = label;
        }
    }

    public record OperationSpec(Dimension dimension, String writeLevel) {}

    /** BO API 每个操作的数据权限维度与写操作级别（主提示词 6.3、7.4 节） */
    public static final Map<String, OperationSpec> OPERATIONS = operations();

    private static Map<String, OperationSpec> operations() {
        Map<String, OperationSpec> map = new LinkedHashMap<>();
        map.put("supplier_list", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("supplier_get", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("rfq_list", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("purchase_order_list", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("purchase_order_get", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("supplier_alternative_list", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("purchase_requisition_draft_create", new OperationSpec(Dimension.purchasingOrg, "L2"));
        map.put("purchase_order_change_delivery_date", new OperationSpec(Dimension.purchasingOrg, "L2"));
        map.put("purchase_order_change_approve", new OperationSpec(Dimension.purchasingOrg, "L2"));
        map.put("delivery_list", new OperationSpec(Dimension.purchasingOrg, "L0"));
        map.put("material_list", new OperationSpec(Dimension.none, "L0"));
        map.put("inventory_balance_list", new OperationSpec(Dimension.company, "L0"));
        map.put("stock_movement_list", new OperationSpec(Dimension.company, "L0"));
        map.put("ap_invoice_list", new OperationSpec(Dimension.company, "L0"));
        map.put("payment_plan_list", new OperationSpec(Dimension.company, "L0"));
        map.put("transaction_anomaly_list", new OperationSpec(Dimension.company, "L0"));
        map.put("transaction_anomaly_get", new OperationSpec(Dimension.company, "L0"));
        map.put("period_close_checklist", new OperationSpec(Dimension.company, "L0"));
        map.put("followup_task_create", new OperationSpec(Dimension.none, "L2"));
        return Map.copyOf(map);
    }

    /**
     * 数据权限范围。
     *
     * @param values 可见的组织编号
     * @param names 可见的组织名称
     * @param filtered 是否因数据权限过滤掉了一部分数据
     */
    public record Scope(Dimension dimension, List<String> values, List<String> names, String label, boolean filtered) {}

    /**
     * 鉴权点 4 通过后的调用上下文。
     *
     * @param agent agent:&lt;namespace&gt;/&lt;name&gt;
     * @param delegationId 受托运行时的委托编号，否则为 null
     */
    public record BoContext(
            String tenantId,
            DemoUser user,
            String agent,
            String agentVersion,
            String mode,
            String delegationId,
            String operation,
            Scope scope) {

        /** 是否在数据权限范围内；维度为 none 时只看租户 */
        public boolean inScope(Object value) {
            return scope.dimension() == Dimension.none || scope.values().contains(String.valueOf(value));
        }

        /** 列表响应：附带数据权限过滤说明（主提示词 6.3 节） */
        public Map<String, Object> listBody(List<?> items) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            body.put("total", items.size());
            body.put("filtered", scope.filtered());
            body.put("visibleScope", scope.names());
            body.put("scopeLabel", scope.label());
            return body;
        }
    }

    /** 令牌验签通过后已知的调用方身份；之后的检查被拒绝时，用它把拒绝记录推送到对应租户的审计 */
    public record CallerIdentity(
            String tenantId, String userId, String agent, String agentVersion, String mode, String delegationId) {}

    /** 结构化错误：错误码、原因、建议的下一步（主提示词 6.3 节） */
    public static final class BoError extends RuntimeException {
        private final int status;
        private final String code;
        private final String suggestion;
        private final CallerIdentity caller;

        public BoError(int status, String code, String reason, String suggestion) {
            this(status, code, reason, suggestion, null);
        }

        BoError(int status, String code, String reason, String suggestion, CallerIdentity caller) {
            super(reason);
            this.status = status;
            this.code = code;
            this.suggestion = suggestion;
            this.caller = caller;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public String reason() {
            return getMessage();
        }

        public String suggestion() {
            return suggestion;
        }

        public CallerIdentity caller() {
            return caller;
        }

        BoError withCaller(CallerIdentity identity) {
            return new BoError(status, code, getMessage(), suggestion, identity);
        }

        public Map<String, Object> body() {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", code);
            error.put("reason", getMessage());
            error.put("suggestion", suggestion);
            return Map.of("error", error);
        }
    }

    public static BoError notFound(String what) {
        return new BoError(404, "NOT_FOUND", what + " 不存在，或不在你的数据权限范围内", "确认单号，或联系有权限的同事");
    }

    /**
     * 业务处理结果。
     *
     * @param target 审计里的目标，例如单据编号
     */
    public record Outcome(int status, Object body, String target, String summary) {
        public static Outcome ok(Object body, String target, String summary) {
            return new Outcome(200, body, target, summary);
        }
    }
}
