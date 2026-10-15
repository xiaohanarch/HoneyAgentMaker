package dev.eab.directory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 演示目录：租户、组织、角色、用户与数据权限（主提示词 6.2 节）。人名、公司名均为虚构。
 * 平台身份服务从这里读用户与租户，DemoERP 从这里读角色权限与数据范围，前端从平台接口读用户列表。
 */
public final class DemoDirectory {

    private DemoDirectory() {}

    public record Organization(String id, String name) {}

    /**
     * @param companies 公司主体，库存与财务数据的权限维度
     * @param purchasingOrgs 采购组织，采购数据的权限维度
     */
    public record Tenant(String id, String name, List<Organization> companies, List<Organization> purchasingOrgs) {}

    public static final List<Tenant> TENANTS = List.of(
            new Tenant(
                    "t001",
                    "星海制造",
                    List.of(new Organization("C100", "星海制造本部"), new Organization("C200", "星海制造华南分公司")),
                    List.of(new Organization("P-EAST", "华东"), new Organization("P-SOUTH", "华南"))),
            new Tenant(
                    "t002",
                    "远航电子",
                    List.of(new Organization("C100", "远航电子总部")),
                    List.of(new Organization("P-EAST", "华东"), new Organization("P-NORTH", "华北"))));

    /** DemoERP 的 BO API 操作，与工具来源 vendor.erp.tools/demo-erp-bo 的工具名、OpenAPI 的 operationId 一一对应 */
    public static final List<String> BO_OPERATIONS = List.of(
            "supplier_list",
            "supplier_get",
            "rfq_list",
            "purchase_order_list",
            "purchase_order_change_delivery_date",
            "purchase_order_change_approve",
            "delivery_list",
            "material_list",
            "inventory_balance_list",
            "stock_movement_list",
            "ap_invoice_list",
            "payment_plan_list",
            "transaction_anomaly_list",
            "transaction_anomaly_get",
            "period_close_checklist",
            "followup_task_create");

    public static boolean isBoOperation(String value) {
        return BO_OPERATIONS.contains(value);
    }

    public record Role(String id, String name, List<String> operations) {}

    /** ERP 角色与它允许的业务操作（业务权限）。没有业务操作的角色只能用 Studio 或治理中心 */
    public static final Map<String, Role> ROLES = roles(
            new Role(
                    "purchasing-manager",
                    "采购经理",
                    List.of(
                            "supplier_list",
                            "supplier_get",
                            "rfq_list",
                            "purchase_order_list",
                            "purchase_order_change_delivery_date",
                            "purchase_order_change_approve",
                            "delivery_list",
                            "material_list",
                            "inventory_balance_list",
                            "followup_task_create")),
            new Role(
                    "purchaser",
                    "采购员",
                    List.of(
                            "supplier_list",
                            "supplier_get",
                            "rfq_list",
                            "purchase_order_list",
                            "purchase_order_change_delivery_date",
                            "delivery_list",
                            "material_list")),
            new Role(
                    "finance-manager",
                    "财务经理",
                    List.of(
                            "supplier_list",
                            "supplier_get",
                            "ap_invoice_list",
                            "payment_plan_list",
                            "transaction_anomaly_list",
                            "transaction_anomaly_get",
                            "period_close_checklist",
                            "followup_task_create")),
            new Role(
                    "inventory-manager",
                    "库存经理",
                    List.of(
                            "material_list",
                            "inventory_balance_list",
                            "stock_movement_list",
                            "purchase_order_list",
                            "delivery_list",
                            "followup_task_create")),
            new Role("tenant-developer", "租户开发者", List.of()),
            new Role("tenant-admin", "租户管理员", List.of()),
            new Role("vendor-developer", "标品开发者", List.of()),
            new Role("platform-operator", "平台运维", List.of()));

    private static Map<String, Role> roles(Role... list) {
        Map<String, Role> map = new LinkedHashMap<>();
        for (Role role : list) map.put(role.id(), role);
        return Map.copyOf(map);
    }

    /** 数据权限：采购域按采购组织过滤，库存与财务按公司主体过滤 */
    public record DataScope(List<String> purchasingOrgs, List<String> companies) {}

    private static final DataScope NO_SCOPE = new DataScope(List.of(), List.of());

    /**
     * @param tenantId 租户编号；厂商研发为 vendor，平台运维为 platform
     * @param dataScopeLabel 界面上展示的数据权限说明
     * @param home 切换到该用户时默认进入的入口：assistant、studio、hub
     */
    public record DemoUser(
            String id,
            String name,
            String tenantId,
            List<String> roles,
            DataScope dataScope,
            String dataScopeLabel,
            String home) {}

    public static final List<DemoUser> USERS = List.of(
            new DemoUser(
                    "zhangmin",
                    "张敏",
                    "t001",
                    List.of("purchasing-manager"),
                    new DataScope(List.of("P-EAST"), List.of("C100")),
                    "采购组织：华东",
                    "assistant"),
            new DemoUser(
                    "liqiang",
                    "李强",
                    "t001",
                    List.of("purchaser"),
                    new DataScope(List.of("P-SOUTH"), List.of("C200")),
                    "采购组织：华南",
                    "assistant"),
            new DemoUser(
                    "wangfang",
                    "王芳",
                    "t001",
                    List.of("finance-manager"),
                    new DataScope(List.of(), List.of("C100")),
                    "公司主体：星海制造本部",
                    "assistant"),
            new DemoUser(
                    "sunkg",
                    "孙库管",
                    "t001",
                    List.of("inventory-manager"),
                    new DataScope(List.of("P-EAST", "P-SOUTH"), List.of("C100", "C200")),
                    "公司主体：全部；采购组织：全部（只读）",
                    "assistant"),
            new DemoUser(
                    "chengong", "陈工", "t001", List.of("tenant-developer"), NO_SCOPE, "无业务数据权限，有 Studio 权限", "studio"),
            new DemoUser("zhaoadmin", "赵管理员", "t001", List.of("tenant-admin"), NO_SCOPE, "管理 Agent 授权与委托", "hub"),
            new DemoUser(
                    "zhouting",
                    "周婷",
                    "t002",
                    List.of("purchasing-manager"),
                    new DataScope(List.of("P-EAST"), List.of("C100")),
                    "采购组织：华东",
                    "assistant"),
            new DemoUser("vendordev", "厂商研发", "vendor", List.of("vendor-developer"), NO_SCOPE, "无任何租户业务数据", "studio"),
            new DemoUser(
                    "platformops", "平台运维", "platform", List.of("platform-operator"), NO_SCOPE, "平台管理，无业务数据", "hub"));

    /** 机机集成账号，只能调用 Open API（主提示词 6.3、7.2 节）；密钥由平台首次启动时生成 */
    public record IntegrationClient(String id, String tenantId, String name) {}

    public static final List<IntegrationClient> INTEGRATION_CLIENTS = List.of(
            new IntegrationClient("t001-integration", "t001", "星海制造集成账号"),
            new IntegrationClient("t002-integration", "t002", "远航电子集成账号"));

    public static Optional<DemoUser> findUser(String id) {
        return USERS.stream().filter(u -> u.id().equals(id)).findFirst();
    }

    public static Optional<Tenant> findTenant(String id) {
        return TENANTS.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    /** 用户的全部业务权限（各角色允许操作的并集） */
    public static List<String> permittedOperations(DemoUser user) {
        Set<String> ops = new LinkedHashSet<>();
        for (String role : user.roles()) ops.addAll(ROLES.get(role).operations());
        return List.copyOf(ops);
    }

    public static String roleNames(DemoUser user) {
        return user.roles().stream().map(r -> ROLES.get(r).name()).collect(Collectors.joining("、"));
    }

    /** 对外展示的用户信息 */
    public record PublicUser(
            String id, String name, String tenantId, String tenantName, String role, String dataScope, String home) {}

    public static PublicUser toPublicUser(DemoUser user) {
        String tenantName = switch (user.tenantId()) {
            case "vendor" -> "厂商";
            case "platform" -> "平台";
            default -> findTenant(user.tenantId()).map(Tenant::name).orElse(user.tenantId());
        };
        return new PublicUser(
                user.id(),
                user.name(),
                user.tenantId(),
                tenantName,
                roleNames(user),
                user.dataScopeLabel(),
                user.home());
    }
}
