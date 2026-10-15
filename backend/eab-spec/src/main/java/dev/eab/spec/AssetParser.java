package dev.eab.spec;

import dev.eab.spec.Layers.Layer;
import dev.eab.spec.kinds.CommonSchemas;
import dev.eab.spec.model.Asset;
import dev.eab.spec.schema.Issues;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;

/**
 * 资产解析：YAML 文本或 JSON 对象 → 校验 → 填充默认值 → 记录类型。
 * 报错带字段路径和中文说明；从 YAML 解析时还带行号和列号，缺少字段时定位到它所在的父对象。
 */
public final class AssetParser {

    private AssetParser() {}

    /** 一个问题。line、column 从 1 开始，没有位置信息时为 null */
    public record AssetIssue(String code, String message, String path, Integer line, Integer column) {

        /** 人读的单行报错：agent.yaml 第 12 行第 5 列 spec.tools[2].mcp.source：…… */
        public String format(String source) {
            String where = line == null ? "" : "第 " + line + " 行第 " + (column == null ? 1 : column) + " 列 ";
            String field = path.isEmpty() ? "" : path + "：";
            String file = source == null ? "" : source + " ";
            return file + where + field + message;
        }
    }

    public sealed interface ParseResult {
        record Ok(Asset asset, JsonNode normalized) implements ParseResult {}

        record Failed(List<AssetIssue> issues) implements ParseResult {}

        default boolean ok() {
            return this instanceof Ok;
        }

        default Asset asset() {
            if (this instanceof Ok success) return success.asset();
            throw new IllegalStateException("资产解析失败：" + issues());
        }

        default List<AssetIssue> issues() {
            return this instanceof Failed failure ? failure.issues() : List.of();
        }
    }

    private record Position(int line, int column) {}

    /** 对已经是 JSON 对象的资产做校验（API 收到 JSON 时使用），没有行号信息 */
    public static ParseResult parseObject(JsonNode value) {
        return validate(value, Map.of());
    }

    /** 解析单个资产的 YAML 文本 */
    public static ParseResult parseYaml(String text) {
        Map<String, Position> positions = new HashMap<>();
        try (JsonParser parser = Json.YAML.createParser(text)) {
            JsonToken first = parser.nextToken();
            if (first == null) return failed("EMPTY_DOCUMENT", "文件里没有资产内容", "", null);
            recordPosition(parser, positions);
            int depth = first.isStructStart() ? 1 : 0;
            while (depth > 0) {
                JsonToken token = parser.nextToken();
                if (token == null) break;
                if (token == JsonToken.PROPERTY_NAME) continue;
                if (token.isStructEnd()) {
                    depth -= 1;
                    continue;
                }
                recordPosition(parser, positions);
                if (token.isStructStart()) depth += 1;
            }
            if (parser.nextToken() != null) return failed("MULTIPLE_DOCUMENTS", "一个文件只能放一个资产", "", null);
        } catch (StreamReadException e) {
            TokenStreamLocation location = e.getLocation();
            String detail = e.getOriginalMessage() == null ? e.getMessage() : e.getOriginalMessage();
            Position at = location == null ? null : new Position(location.getLineNr(), location.getColumnNr());
            return failed(
                    "YAML_SYNTAX", "YAML 语法错误：" + detail.lines().findFirst().orElse(""), "", at);
        }
        JsonNode tree = Json.YAML.readTree(text);
        return validate(tree, positions);
    }

    /** 记录当前值所在的位置，键是 JSON Pointer，例如 /spec/tools/2/mcp/source */
    private static void recordPosition(JsonParser parser, Map<String, Position> positions) {
        TokenStreamLocation location = parser.currentTokenLocation();
        String pointer = parser.streamReadContext().pathAsPointer().toString();
        if (parser.currentToken().isStructStart()) {
            pointer = parser.streamReadContext().getParent() == null
                    ? ""
                    : parser.streamReadContext().getParent().pathAsPointer().toString();
        }
        positions.putIfAbsent(pointer, new Position(location.getLineNr(), location.getColumnNr()));
    }

    private static ParseResult validate(JsonNode value, Map<String, Position> positions) {
        if (value == null || !value.isObject()) {
            return failed("NOT_AN_OBJECT", "资产必须是一个对象", "", null);
        }
        if (!CommonSchemas.API_VERSION.equals(textOf(value, "apiVersion"))) {
            return failedAt(
                    "UNSUPPORTED_API_VERSION",
                    "apiVersion 必须是 " + CommonSchemas.API_VERSION,
                    List.of("apiVersion"),
                    positions);
        }
        String kindName = textOf(value, "kind");
        AssetKind kind = AssetKind.of(kindName == null ? "" : kindName).orElse(null);
        if (kind == null) {
            String known = String.join(
                    "、", Arrays.stream(AssetKind.values()).map(Enum::name).toList());
            return failedAt(
                    "UNKNOWN_KIND", "未知的 kind \"" + kindName + "\"，可用的 kind：" + known, List.of("kind"), positions);
        }

        Issues issues = new Issues();
        JsonNode normalized;
        try {
            normalized = kind.schema().parse(value, List.of(), issues);
        } catch (RuntimeException e) {
            // schema 里的校验函数不应抛异常；万一抛了，也转成问题返回，不让调用方崩溃
            return failed("SCHEMA_INTERNAL", "校验时发生内部错误：" + e.getMessage(), "", null);
        }
        if (!issues.isEmpty() || normalized == null) {
            List<AssetIssue> list = new ArrayList<>();
            for (Issues.Issue issue : issues.list()) {
                Position at = locate(issue.path(), positions);
                list.add(new AssetIssue(
                        "SCHEMA_" + issue.code().toUpperCase(),
                        issue.message(),
                        Issues.formatPath(issue.path()),
                        at == null ? null : at.line(),
                        at == null ? null : at.column()));
            }
            return new ParseResult.Failed(list);
        }

        Asset asset = Json.fromTree(normalized, kind.type());
        List<Layer> allowed = kind.allowedLayers();
        Layer layer = asset.layer();
        if (!allowed.isEmpty() && !allowed.contains(layer)) {
            String names = String.join("、", allowed.stream().map(Enum::name).toList());
            return failedAt(
                    "KIND_LAYER_MISMATCH",
                    kind.name() + " 只能放在 " + names + " 层的命名空间里，当前是 " + layer.name() + " 层",
                    List.of("metadata", "namespace"),
                    positions);
        }
        return new ParseResult.Ok(asset, normalized);
    }

    private static String textOf(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node != null && node.isString() ? node.asString() : null;
    }

    /** 从最深的路径开始往上找，取第一个有位置信息的节点 */
    private static Position locate(List<Object> path, Map<String, Position> positions) {
        if (positions.isEmpty()) return null;
        for (int depth = path.size(); depth >= 0; depth--) {
            StringBuilder pointer = new StringBuilder();
            for (Object key : path.subList(0, depth)) {
                pointer.append('/').append(key.toString().replace("~", "~0").replace("/", "~1"));
            }
            Position position = positions.get(pointer.toString());
            if (position != null) return position;
        }
        return null;
    }

    private static ParseResult failed(String code, String message, String path, Position at) {
        return new ParseResult.Failed(List.of(
                new AssetIssue(code, message, path, at == null ? null : at.line(), at == null ? null : at.column())));
    }

    private static ParseResult failedAt(
            String code, String message, List<Object> path, Map<String, Position> positions) {
        return failed(code, message, Issues.formatPath(path), locate(path, positions));
    }
}
