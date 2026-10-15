package dev.eab.erp.meta;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 元数据服务（/meta/v1）：对象、字段、枚举、规则、权限维度，带版本 */
@RestController
public class MetaController {

    @GetMapping("/meta/v1/objects")
    public Map<String, Object> objects() {
        List<Map<String, Object>> items = MetaCatalog.OBJECTS.stream()
                .map(o -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("ref", MetaCatalog.versionedRef(o));
                    item.put("label", o.get("label"));
                    item.put("domain", o.get("domain"));
                    item.put("version", o.get("version"));
                    item.put("permissionDimension", o.get("permissionDimension"));
                    return item;
                })
                .toList();
        return Map.of("items", items);
    }

    @GetMapping("/meta/v1/objects/{ref}")
    public ResponseEntity<Object> object(@PathVariable String ref) {
        return MetaCatalog.find(ref)
                .<ResponseEntity<Object>>map(o -> {
                    Map<String, Object> body = new LinkedHashMap<>(o);
                    body.put("ref", MetaCatalog.versionedRef(o));
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(404)
                        .body(Map.of(
                                "error",
                                Map.of(
                                        "code", "NOT_FOUND",
                                        "reason", "元数据对象 " + ref + " 不存在或版本不符",
                                        "suggestion", "先查询 /meta/v1/objects"))));
    }

    @GetMapping("/meta/v1/rules")
    public Map<String, Object> rules() {
        return Map.of("items", MetaCatalog.RULES);
    }

    @GetMapping("/meta/v1/permission-dimensions")
    public Map<String, Object> permissionDimensions() {
        return Map.of("items", List.of(dimension("采购组织"), dimension("公司主体")));
    }

    private static Map<String, Object> dimension(String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put(
                "appliesTo",
                MetaCatalog.OBJECTS.stream()
                        .filter(o -> name.equals(o.get("permissionDimension")))
                        .map(o -> o.get("ref"))
                        .toList());
        return item;
    }
}
