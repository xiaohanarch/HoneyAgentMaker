package dev.eab.erp.meta;

import dev.eab.erp.ErpJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.type.TypeReference;

/**
 * 元数据服务的内容（主提示词 6.4 节）：对象、字段、枚举、规则、权限维度，带版本。
 * 语义包的投影段只写引用（如 erp.pur.Supplier@v1），运行时从这里取定义。内容在 meta/catalog.json。
 */
public final class MetaCatalog {

    private MetaCatalog() {}

    private record Catalog(List<Map<String, Object>> objects, List<Map<String, Object>> rules) {}

    private static final Catalog CATALOG = load();

    public static final List<Map<String, Object>> OBJECTS = CATALOG.objects();
    public static final List<Map<String, Object>> RULES = CATALOG.rules();

    private static final Pattern REF = Pattern.compile("^(erp\\.[a-z]+\\.[A-Za-z]+)(?:@v(\\d+))?$");

    private static Catalog load() {
        try (InputStream in = MetaCatalog.class.getResourceAsStream("/meta/catalog.json")) {
            if (in == null) throw new IllegalStateException("缺少 meta/catalog.json");
            Map<String, List<Map<String, Object>>> data =
                    ErpJson.MAPPER.readValue(in, new TypeReference<Map<String, List<Map<String, Object>>>>() {});
            return new Catalog(List.copyOf(data.get("objects")), List.copyOf(data.get("rules")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** erp.pur.Supplier@v1 → 对象；版本不符时返回空 */
    public static Optional<Map<String, Object>> find(String ref) {
        Matcher match = REF.matcher(ref);
        if (!match.matches()) return Optional.empty();
        return OBJECTS.stream()
                .filter(o -> o.get("ref").equals(match.group(1)))
                .findFirst()
                .filter(o -> match.group(2) == null
                        || Long.parseLong(match.group(2)) == ((Number) o.get("version")).longValue());
    }

    public static String versionedRef(Map<String, Object> object) {
        return object.get("ref") + "@v" + object.get("version");
    }
}
