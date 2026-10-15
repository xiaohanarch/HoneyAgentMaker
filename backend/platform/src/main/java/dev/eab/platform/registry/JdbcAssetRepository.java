package dev.eab.platform.registry;

import dev.eab.spec.AssetResolver.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** 资产存储的 SQLite 实现 */
public final class JdbcAssetRepository implements AssetRepository {

    private final JdbcClient jdbc;

    public JdbcAssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AssetRecord insert(NewAssetRecord r) {
        long id = jdbc.sql("""
                        INSERT INTO assets (kind, namespace, name, version, layer, tenant_id, status, content_yaml,
                          content_json, content_hash, signature, change_note, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'draft', ?, ?, ?, NULL, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .params(
                        r.kind(),
                        r.namespace(),
                        r.name(),
                        r.version(),
                        r.layer(),
                        r.tenantId(),
                        r.contentYaml(),
                        r.contentJson(),
                        r.contentHash(),
                        r.changeNote(),
                        r.createdBy(),
                        r.createdAt(),
                        r.createdAt())
                .query(Long.class)
                .single();
        return byId(id);
    }

    private AssetRecord byId(long id) {
        return jdbc.sql("SELECT * FROM assets WHERE id = ?")
                .param(id)
                .query(JdbcAssetRepository::map)
                .single();
    }

    @Override
    public Optional<AssetRecord> find(String namespace, String name, String version) {
        return jdbc.sql("SELECT * FROM assets WHERE namespace = ? AND name = ? AND version = ?")
                .params(namespace, name, version)
                .query(JdbcAssetRepository::map)
                .optional();
    }

    @Override
    public List<AssetRecord> listVersions(String namespace, String name) {
        return jdbc.sql("SELECT * FROM assets WHERE namespace = ? AND name = ? ORDER BY id")
                .params(namespace, name)
                .query(JdbcAssetRepository::map)
                .list();
    }

    @Override
    public List<AssetRecord> list(AssetFilter f) {
        StringBuilder sql = new StringBuilder("SELECT * FROM assets WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        condition(sql, params, "kind", f.kind());
        condition(sql, params, "namespace", f.namespace());
        condition(sql, params, "layer", f.layer());
        condition(sql, params, "tenant_id", f.tenantId());
        condition(sql, params, "status", f.status() == null ? null : f.status().name());
        sql.append(" ORDER BY namespace, name, id");
        return jdbc.sql(sql.toString())
                .params(params)
                .query(JdbcAssetRepository::map)
                .list();
    }

    private static void condition(StringBuilder sql, List<Object> params, String column, String value) {
        if (value == null) return;
        sql.append(" AND ").append(column).append(" = ?");
        params.add(value);
    }

    @Override
    public AssetRecord updateStatus(long id, Status status, String signature, String updatedAt) {
        jdbc.sql("UPDATE assets SET status = ?, signature = ?, updated_at = ? WHERE id = ?")
                .params(status.name(), signature, updatedAt, id)
                .update();
        return byId(id);
    }

    private static AssetRecord map(ResultSet rs, int row) throws SQLException {
        return new AssetRecord(
                rs.getLong("id"),
                rs.getString("kind"),
                rs.getString("namespace"),
                rs.getString("name"),
                rs.getString("version"),
                rs.getString("layer"),
                rs.getString("tenant_id"),
                Status.valueOf(rs.getString("status")),
                rs.getString("content_yaml"),
                rs.getString("content_json"),
                rs.getString("content_hash"),
                rs.getString("signature"),
                rs.getString("change_note"),
                rs.getString("created_by"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }
}
