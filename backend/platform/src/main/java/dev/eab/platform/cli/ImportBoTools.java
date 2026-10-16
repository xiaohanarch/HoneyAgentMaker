package dev.eab.platform.cli;

import dev.eab.platform.PlatformProperties;
import dev.eab.spec.AssetParser;
import dev.eab.spec.Json;
import dev.eab.spec.model.Asset.ToolSourceAsset;
import dev.eab.spec.model.ToolSourceSpec.ToolDefinition;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * pnpm tools:import-bo [--url http://127.0.0.1:4100] [--write]
 *
 * <p>BO 工具来源从 DemoERP 的 OpenAPI 生成（03 文档 P3 第 2 点）：读取 /bo/v1/openapi.json，按操作生成工具的入参 JSON Schema，
 * 与 assets/vendor/erp/demo-erp-bo.yaml 比对。资产仍是唯一来源：不一致时报错；加 --write 时只把生成的 inputSchema 写回资产，
 * 并升次版本号。写级别、方法、路径不一致说明 ERP 与资产有分歧，需要人工确认，不自动改写。
 */
public final class ImportBoTools {

    private ImportBoTools() {}

    static final String ASSET = "vendor/erp/demo-erp-bo.yaml";

    /** 从 OpenAPI 生成的一个工具 */
    public record Generated(String name, String method, String path, String writeLevel, ObjectNode inputSchema) {}

    /** 路径参数、查询参数、请求体字段合成一个对象 schema；必填项合并 */
    public static Map<String, Generated> fromOpenApi(JsonNode doc) {
        Map<String, Generated> tools = new LinkedHashMap<>();
        for (var path : doc.path("paths").properties()) {
            for (var method : path.getValue().properties()) {
                JsonNode op = method.getValue();
                String name = op.path("operationId").asString();
                if (name.isEmpty()) continue;
                ObjectNode schema = Json.MAPPER.createObjectNode();
                schema.put("type", "object");
                ObjectNode properties = schema.putObject("properties");
                List<String> required = new ArrayList<>();
                for (JsonNode parameter : op.path("parameters")) {
                    ObjectNode property = ((ObjectNode) parameter.path("schema")).deepCopy();
                    if (parameter.has("description")) property.put("description", parameter.path("description").asString());
                    properties.set(parameter.path("name").asString(), property);
                    if (parameter.path("required").asBoolean(false)) required.add(parameter.path("name").asString());
                }
                JsonNode body = op.path("requestBody").path("content").path("application/json").path("schema");
                for (var field : body.path("properties").properties()) properties.set(field.getKey(), field.getValue().deepCopy());
                for (JsonNode r : body.path("required")) required.add(r.asString());
                if (!required.isEmpty()) {
                    ArrayNode list = schema.putArray("required");
                    required.forEach(list::add);
                }
                tools.put(name, new Generated(
                        name, method.getKey().toUpperCase(), path.getKey(), op.path("x-write-level").asString(), schema));
            }
        }
        return tools;
    }

    /** 资产与生成结果的差异；inputSchema 之外的差异需要人工处理 */
    public record Diff(List<String> blocking, List<String> schemaChanges) {
        public boolean clean() {
            return blocking.isEmpty() && schemaChanges.isEmpty();
        }
    }

    public static Diff compare(ToolSourceAsset asset, Map<String, Generated> generated) {
        List<String> blocking = new ArrayList<>();
        List<String> schemaChanges = new ArrayList<>();
        Map<String, ToolDefinition> declared = new LinkedHashMap<>();
        for (ToolDefinition tool : asset.spec().tools()) declared.put(tool.name(), tool);
        for (String name : generated.keySet()) {
            if (!declared.containsKey(name)) blocking.add("OpenAPI 有操作 " + name + "，资产里没有对应的工具");
        }
        for (ToolDefinition tool : declared.values()) {
            Generated g = generated.get(tool.name());
            if (g == null) {
                blocking.add("资产里的工具 " + tool.name() + " 在 OpenAPI 里不存在");
                continue;
            }
            if (!g.writeLevel().equals(tool.writeLevel())) {
                blocking.add(tool.name() + " 的写级别：资产 " + tool.writeLevel() + "，OpenAPI " + g.writeLevel());
            }
            if (tool.operation() == null
                    || !g.method().equalsIgnoreCase(tool.operation().method())
                    || !g.path().equals(tool.operation().path())) {
                blocking.add(tool.name() + " 的方法或路径与 OpenAPI 不一致");
            }
            if (tool.inputSchema() == null || !tool.inputSchema().equals(g.inputSchema())) {
                schemaChanges.add(tool.name());
            }
        }
        return new Diff(blocking, schemaChanges);
    }

    private static final Pattern NAME = Pattern.compile("^\\s*- name: (\\S+)\\s*$");
    private static final Pattern VERSION = Pattern.compile("^(\\s*version: )(\\d+)\\.(\\d+)\\.(\\d+)\\s*$");

    /**
     * 把生成的 inputSchema 写进资产文本：逐行处理，保留注释和人工写的描述；每个工具的 operation 行之后是 inputSchema 行
     * （JSON 写法，也是合法的 YAML），并把 metadata 里的版本号升一个次版本。
     */
    public static String rewrite(String yaml, Map<String, Generated> generated) {
        List<String> out = new ArrayList<>();
        String current = null;
        boolean versionBumped = false;
        String[] lines = yaml.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher version = VERSION.matcher(line);
            if (!versionBumped && version.matches()) {
                out.add(version.group(1) + version.group(2) + "." + (Integer.parseInt(version.group(3)) + 1) + ".0");
                versionBumped = true;
                continue;
            }
            Matcher name = NAME.matcher(line);
            if (name.matches()) current = name.group(1);
            if (line.strip().startsWith("inputSchema:")) continue;
            out.add(line);
            if (line.strip().startsWith("operation:") && current != null && generated.containsKey(current)) {
                String indent = line.substring(0, line.indexOf("operation:"));
                out.add(indent + "inputSchema: " + Json.MAPPER.writeValueAsString(generated.get(current).inputSchema()));
            }
        }
        return String.join("\n", out);
    }

    static JsonNode fetch(String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url).resolve("/bo/v1/openapi.json")).GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("读取 OpenAPI 失败：HTTP " + response.statusCode());
        return Json.MAPPER.readTree(response.body());
    }

    public static int run(JsonNode openApi, Path assetsRoot, boolean write, PrintStream out) throws IOException {
        Path file = assetsRoot.resolve(ASSET);
        String yaml = Files.readString(file);
        var parsed = AssetParser.parseYaml(yaml);
        if (!parsed.ok() || !(parsed.asset() instanceof ToolSourceAsset asset)) {
            out.println(Ansi.color("31", "资产 " + ASSET + " 不是合法的工具来源"));
            return 1;
        }
        Map<String, Generated> generated = fromOpenApi(openApi);
        Diff diff = compare(asset, generated);
        diff.blocking().forEach(m -> out.println(Ansi.color("31", "✗ " + m)));
        if (!diff.blocking().isEmpty()) return 1;
        if (diff.schemaChanges().isEmpty()) {
            out.println(Ansi.color("32", "资产与 DemoERP 的 OpenAPI 一致，共 " + generated.size() + " 个工具"));
            return 0;
        }
        out.println("入参 schema 与 OpenAPI 不一致的工具：" + String.join("、", diff.schemaChanges()));
        if (!write) {
            out.println("加 --write 写回资产（会升次版本号）");
            return 1;
        }
        Files.writeString(file, rewrite(yaml, generated));
        out.println(Ansi.color("32", "已写回 " + ASSET + "，请检查差异并提交"));
        return 0;
    }

    public static void main(String[] args) {
        Ansi.quietLogs();
        String url = "http://127.0.0.1:4100";
        boolean write = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--write")) write = true;
            else if (args[i].equals("--url") && i + 1 < args.length) url = args[++i];
        }
        try {
            System.exit(run(fetch(url), PlatformProperties.repoRoot().resolve("assets"), write, System.out));
        } catch (IOException e) {
            System.err.println("无法读取 DemoERP 的 OpenAPI（" + url + "）：" + e.getMessage() + "。先运行 pnpm demo");
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }
}
