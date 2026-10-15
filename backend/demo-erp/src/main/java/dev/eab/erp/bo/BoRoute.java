package dev.eab.erp.bo;

import dev.eab.erp.bo.BoTypes.BoContext;
import dev.eab.erp.bo.BoTypes.Outcome;
import java.util.List;
import java.util.Map;

/**
 * 一个 BO API 操作的声明：operationId 与工具名一致，参数声明同时用于校验和生成 OpenAPI。
 *
 * @param path 路径模板，例如 /bo/v1/suppliers/{supplierId}
 */
public record BoRoute(
        String operation,
        String method,
        String path,
        String summary,
        String description,
        List<Param> params,
        List<Param> query,
        List<Param> body,
        Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Outcome handle(BoContext ctx, Input input);
    }

    /** 校验并转换后的输入；没有提供的可选参数不出现在 Map 里 */
    public record Input(Map<String, Object> params, Map<String, Object> query, Map<String, Object> body) {

        public String param(String name) {
            return (String) params.get(name);
        }

        public String query(String name) {
            return (String) query.get(name);
        }

        public Long queryLong(String name) {
            Object value = query.get(name);
            return value == null ? null : ((Number) value).longValue();
        }

        public Boolean queryBool(String name) {
            return (Boolean) query.get(name);
        }

        public int limit() {
            return queryLong("limit").intValue();
        }

        public String body(String name) {
            return (String) body.get(name);
        }
    }

    public static Builder get(String operation, String path) {
        return new Builder(operation, "GET", path);
    }

    public static Builder post(String operation, String path) {
        return new Builder(operation, "POST", path);
    }

    public static final class Builder {
        private final String operation;
        private final String method;
        private final String path;
        private String summary = "";
        private String description = "";
        private List<Param> params = List.of();
        private List<Param> query = List.of();
        private List<Param> body;

        private Builder(String operation, String method, String path) {
            this.operation = operation;
            this.method = method;
            this.path = path;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        /** 路径参数一律必填 */
        public Builder params(Param... params) {
            this.params = List.of(params).stream().map(Param::mandatory).toList();
            return this;
        }

        public Builder query(Param... query) {
            this.query = List.of(query);
            return this;
        }

        public Builder body(Param... body) {
            this.body = List.of(body);
            return this;
        }

        public BoRoute handler(Handler handler) {
            return new BoRoute(operation, method, path, summary, description, params, query, body, handler);
        }
    }
}
