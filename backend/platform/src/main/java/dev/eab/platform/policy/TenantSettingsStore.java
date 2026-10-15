package dev.eab.platform.policy;

import dev.eab.platform.IsoTime;
import dev.eab.spec.Json;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;

/** 租户级的鉴权开关：L3 自主写是否开启、岗位助手开放给哪些角色 */
public final class TenantSettingsStore {

    /**
     * @param l3Enabled L3 自主写是否开启（主提示词 7.4 节：需要租户管理员显式开启）
     * @param assistantRoles 岗位助手开放给哪些角色；没有登记的助手对所有有业务权限的角色开放
     */
    public record TenantSettings(String tenantId, boolean l3Enabled, Map<String, List<String>> assistantRoles) {}

    /** 演示租户的默认设置：L3 关闭，岗位助手按岗位开放 */
    static final Map<String, List<String>> DEFAULT_ASSISTANT_ROLES = Map.of(
            "vendor.pur/procurement-assistant", List.of("purchasing-manager", "purchaser", "inventory-manager"),
            "vendor.scm/inventory-assistant", List.of("inventory-manager", "purchasing-manager"),
            "vendor.fin/finance-assistant", List.of("finance-manager"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public TenantSettingsStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public TenantSettings get(String tenantId) {
        return jdbc.sql("SELECT * FROM tenant_settings WHERE tenant_id = ?")
                .param(tenantId)
                .query((rs, row) -> new TenantSettings(
                        tenantId,
                        rs.getInt("l3_enabled") != 0,
                        Json.MAPPER.readValue(rs.getString("assistant_roles"), new TypeReference<Map<String, List<String>>>() {})))
                .optional()
                .orElse(new TenantSettings(tenantId, false, DEFAULT_ASSISTANT_ROLES));
    }

    public TenantSettings set(TenantSettings settings) {
        jdbc.sql("""
                        INSERT INTO tenant_settings (tenant_id, l3_enabled, assistant_roles, updated_at) VALUES (?, ?, ?, ?)
                        ON CONFLICT (tenant_id) DO UPDATE SET l3_enabled = excluded.l3_enabled,
                          assistant_roles = excluded.assistant_roles, updated_at = excluded.updated_at
                        """)
                .params(settings.tenantId(), settings.l3Enabled() ? 1 : 0,
                        Json.MAPPER.writeValueAsString(settings.assistantRoles()), IsoTime.now(clock))
                .update();
        return get(settings.tenantId());
    }
}
