package dev.eab.erp.bo;

import dev.eab.common.IsoTime;
import dev.eab.erp.ErpJson;
import dev.eab.erp.bo.BoRoute.Input;
import dev.eab.erp.bo.BoTypes.BoContext;
import dev.eab.erp.bo.BoTypes.BoError;
import dev.eab.erp.bo.BoTypes.CallerIdentity;
import dev.eab.erp.bo.BoTypes.Outcome;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.db.Rows;
import dev.eab.erp.link.PlatformLink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * BO API 的调度：匹配路由 → 校验参数 → 鉴权点 4 → 幂等键 → 业务处理 → 访问日志与平台审计。
 * 写操作支持 Idempotency-Key；每次裁决写访问日志并推送到平台审计。
 */
public final class BoApi {

    private static final int MAX_KEY_LENGTH = 128;

    /**
     * 一次 BO 请求。
     *
     * @param path 原始路径（未解码）
     * @param rawQuery 原始查询字符串，没有时为 null
     * @param queryParams 解码后的查询参数，同名参数取第一个
     */
    public record Request(
            String method,
            String path,
            String rawQuery,
            Map<String, String> queryParams,
            byte[] body,
            Function<String, String> header) {

        /** 与 TS 版 request.url 相同：路径加查询字符串 */
        String url() {
            return rawQuery == null ? path : path + "?" + rawQuery;
        }
    }

    public record Response(int status, Map<String, String> headers, Object body) {}

    private record Match(BoRoute route, Map<String, String> pathValues) {}

    private final ErpDb db;
    private final PlatformLink link;
    private final Clock clock;
    private final BoAuthorizer authorizer;
    private final List<BoRoute> routes;

    public BoApi(ErpDb db, PlatformLink link, Clock clock, List<BoRoute> routes) {
        this.db = db;
        this.link = link;
        this.clock = clock;
        this.authorizer = new BoAuthorizer(link);
        this.routes = List.copyOf(routes);
    }

    public List<BoRoute> routes() {
        return routes;
    }

    public Response handle(Request request) {
        Optional<Match> match = match(request.method(), request.path());
        if (match.isEmpty()) {
            return error(new BoError(
                    404, "NOT_FOUND", "没有这个接口：" + request.method() + " " + request.path(), "查看 /bo/v1/openapi.json"));
        }
        BoRoute route = match.get().route();
        List<Param.Problem> problems = new ArrayList<>();
        Input input = parseInput(route, match.get().pathValues(), request, problems);
        if (!problems.isEmpty()) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "BAD_REQUEST");
            error.put("reason", "请求参数不合法");
            error.put("suggestion", "检查参数格式");
            error.put("details", problems);
            return new Response(400, Map.of(), Map.of("error", error));
        }
        return execute(route, input, request);
    }

    private Response execute(BoRoute route, Input input, Request request) {
        String operation = route.operation();
        boolean isWrite = !BoTypes.OPERATIONS.get(operation).writeLevel().equals("L0");
        BoContext ctx = null;
        try {
            ctx = authorizer.authorize(request.header().apply("authorization"), operation);
            String key = request.header().apply("idempotency-key");
            String fingerprint = sha256(request.method() + " " + request.url() + " "
                    + ErpJson.MAPPER.writeValueAsString(route.body() == null ? null : input.body()));
            if (key != null && key.length() > MAX_KEY_LENGTH) {
                throw new BoError(
                        400, "IDEMPOTENCY_KEY_TOO_LONG", "幂等键不能超过 " + MAX_KEY_LENGTH + " 个字符", "使用较短的幂等键，例如 UUID");
            }
            boolean idempotent = isWrite && key != null && !key.isEmpty();
            if (idempotent) {
                Optional<Response> replay = replay(ctx, operation, key, fingerprint, request);
                if (replay.isPresent()) return replay.get();
            }
            BoContext context = ctx;
            Outcome outcome = isWrite
                    ? db.transaction(() -> {
                        Outcome result = route.handler().handle(context, input);
                        if (idempotent) storeIdempotent(context, operation, key, fingerprint, result);
                        return result;
                    })
                    : route.handler().handle(ctx, input);
            record(new Entry(
                    ctx,
                    operation,
                    ctx.user().id(),
                    ctx.agent(),
                    ctx.agentVersion(),
                    ctx.tenantId(),
                    ctx.mode(),
                    "allow",
                    "OK",
                    "允许",
                    outcome.target(),
                    outcome.summary()));
            return new Response(outcome.status(), Map.of(), outcome.body());
        } catch (BoError error) {
            // 鉴权通过后在业务处理中被拒绝（例如职责分离）时用调用上下文；鉴权阶段被拒绝时用令牌里的身份
            CallerIdentity caller = ctx == null
                    ? error.caller()
                    : new CallerIdentity(
                            ctx.tenantId(), ctx.user().id(), ctx.agent(), ctx.agentVersion(), ctx.mode(), null);
            record(new Entry(
                    ctx,
                    operation,
                    caller == null ? "unknown" : caller.userId(),
                    caller == null ? null : caller.agent(),
                    caller == null ? null : caller.agentVersion(),
                    caller == null ? null : caller.tenantId(),
                    caller == null ? "unknown" : caller.mode(),
                    "deny",
                    error.code(),
                    error.reason(),
                    request.path(),
                    "拒绝"));
            return error(error);
        }
    }

    /** 只给同一个用户、同一份请求内容回放结果；否则拒绝，不泄露别人那次请求的响应 */
    private Optional<Response> replay(
            BoContext ctx, String operation, String key, String fingerprint, Request request) {
        Optional<Map<String, Object>> stored = Rows.one(
                db.jdbc(),
                "SELECT * FROM idempotency_keys WHERE tenant_id = ? AND operation = ? AND key = ?",
                ctx.tenantId(),
                operation,
                key);
        if (stored.isEmpty()) return Optional.empty();
        Map<String, Object> row = stored.get();
        if (!ctx.user().id().equals(row.get("userId")) || !fingerprint.equals(row.get("fingerprint"))) {
            throw new BoError(422, "IDEMPOTENCY_KEY_REUSED", "这个幂等键已被另一份请求使用", "每份请求使用新的幂等键");
        }
        record(new Entry(
                ctx,
                operation,
                ctx.user().id(),
                ctx.agent(),
                ctx.agentVersion(),
                ctx.tenantId(),
                ctx.mode(),
                "allow",
                "IDEMPOTENT_REPLAY",
                "幂等重放，返回首次结果，未重复执行",
                request.path(),
                "幂等重放"));
        Object body = ErpJson.MAPPER.readValue(Rows.str(row, "response"), Object.class);
        int status = ((Number) row.get("statusCode")).intValue();
        return Optional.of(new Response(status, Map.of("idempotent-replayed", "true"), body));
    }

    private void storeIdempotent(BoContext ctx, String operation, String key, String fingerprint, Outcome outcome) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", ctx.tenantId());
        row.put("operation", operation);
        row.put("key", key);
        row.put("userId", ctx.user().id());
        row.put("fingerprint", fingerprint);
        row.put("statusCode", outcome.status());
        row.put("response", ErpJson.MAPPER.writeValueAsString(outcome.body()));
        row.put("createdAt", IsoTime.now(clock));
        Rows.insert(db.jdbc(), "idempotency_keys", row);
    }

    private record Entry(
            BoContext ctx,
            String operation,
            String principal,
            String agent,
            String agentVersion,
            String tenantId,
            String mode,
            String outcome,
            String code,
            String reason,
            String target,
            String summary) {}

    /** 写访问日志；知道租户时推送 bo.access 到平台审计 */
    private void record(Entry entry) {
        String at = IsoTime.now(clock);
        String dataScope = entry.ctx() == null ? "" : entry.ctx().scope().label();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", at);
        row.put("tenantId", entry.tenantId());
        row.put("api", "bo");
        row.put("operation", entry.operation());
        row.put("principal", entry.principal());
        row.put("agent", entry.agent());
        row.put("mode", entry.mode());
        row.put("outcome", entry.outcome());
        row.put("code", entry.code());
        row.put("reason", entry.reason());
        row.put("dataScope", dataScope);
        row.put("target", entry.target());
        Rows.insert(db.jdbc(), "access_log", row);
        if (entry.tenantId() == null) return;
        String version =
                entry.ctx() == null ? entry.agentVersion() : entry.ctx().agentVersion();
        String agent = (entry.agent() == null ? "" : entry.agent()).replaceFirst("^agent:", "")
                + (version == null || version.isEmpty() ? "" : "@" + version);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("at", at);
        payload.put("operation", entry.operation());
        payload.put("userId", entry.principal());
        payload.put("agent", agent);
        payload.put("mode", entry.mode());
        if (entry.ctx() != null && entry.ctx().delegationId() != null) {
            payload.put("delegationId", entry.ctx().delegationId());
        }
        payload.put("outcome", entry.outcome());
        payload.put("code", entry.code());
        payload.put("reason", entry.reason());
        payload.put("dataScope", dataScope);
        payload.put("target", entry.target());
        payload.put("resultSummary", entry.summary());
        link.publish("bo.access", entry.tenantId(), payload);
    }

    private static Response error(BoError error) {
        return new Response(error.status(), Map.of(), error.body());
    }

    private Optional<Match> match(String method, String path) {
        String[] actual = path.split("/", -1);
        for (BoRoute route : routes) {
            if (!route.method().equals(method)) continue;
            String[] template = route.path().split("/", -1);
            if (template.length != actual.length) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean matched = true;
            for (int i = 0; i < template.length && matched; i++) {
                String part = template[i];
                if (part.startsWith("{") && part.endsWith("}")) {
                    String value = UriUtils.decode(actual[i], StandardCharsets.UTF_8);
                    if (value.isEmpty()) matched = false;
                    values.put(part.substring(1, part.length() - 1), value);
                } else {
                    matched = part.equals(actual[i]);
                }
            }
            if (matched) return Optional.of(new Match(route, values));
        }
        return Optional.empty();
    }

    private static Input parseInput(
            BoRoute route, Map<String, String> pathValues, Request request, List<Param.Problem> problems) {
        Map<String, Object> params = parseAll(route.params(), pathValues::get, problems);
        Map<String, Object> query = parseAll(route.query(), request.queryParams()::get, problems);
        Map<String, Object> body = Map.of();
        if (route.body() != null) {
            Map<String, Object> raw = parseJsonObject(request.body());
            if (raw == null) {
                problems.add(new Param.Problem("body", "请求体必须是 JSON 对象"));
            } else {
                body = parseAll(route.body(), raw::get, problems);
            }
        }
        return new Input(params, query, body);
    }

    private static Map<String, Object> parseAll(
            List<Param> specs, Function<String, Object> source, List<Param.Problem> problems) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Param spec : specs) {
            Object value = spec.parse(source.apply(spec.name()), problems);
            if (value != null) values.put(spec.name(), value);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JsonNode node = ErpJson.MAPPER.readTree(body);
            if (node == null || !node.isObject()) return null;
            return ErpJson.MAPPER.convertValue(node, Map.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256", e);
        }
    }
}
