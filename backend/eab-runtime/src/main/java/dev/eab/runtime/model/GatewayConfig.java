package dev.eab.runtime.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 模型网关的配置，来自环境变量。Agent 只引用档位（fast、reasoning、judge），档位到模型名的映射在这里。
 * Key 只从环境变量读取，不写入任何文件；选择了真实提供方却缺少 Key 或模型名时启动即报错，而不是静默退回脚本化模型。
 *
 * @param prices 每百万 token 的美元单价，用于估算成本
 * @param maxTokens 单次调用的输出上限。带推理过程的模型（例如 GLM-5.3）推理也计入输出，上限太小时会没有正文
 * @param timeoutSeconds 单次请求的超时。厂商模块默认 60 秒，推理模型处理大输入时常常超过
 * @param maxRetries 请求失败或超时后的重试次数。厂商模块默认 3 次，慢模型超时后重发同样的请求只会更慢
 */
public record GatewayConfig(
        String provider,
        Map<String, String> profiles,
        String baseUrl,
        String apiKey,
        Map<String, Price> prices,
        int maxTokens,
        int timeoutSeconds,
        int maxRetries) {

    public static final int DEFAULT_MAX_TOKENS = 16_384;
    static final int MIN_MAX_TOKENS = 256;
    static final int MAX_MAX_TOKENS = 131_072;
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;
    public static final int DEFAULT_MAX_RETRIES = 1;

    public static final List<String> PROVIDERS = List.of("scripted", "openai", "anthropic");
    public static final List<String> PROFILES = List.of("fast", "reasoning", "judge");

    public record Price(double input, double output) {}

    public static final class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }

    private static final Map<String, Map<String, String>> DEFAULT_PROFILES = Map.of(
            "scripted", Map.of("fast", "scripted", "reasoning", "scripted", "judge", "scripted"),
            "anthropic",
                    Map.of("fast", "claude-haiku-5-5", "reasoning", "claude-sonnet-5-5", "judge", "claude-opus-5-5"),
            "openai", Map.of("fast", "", "reasoning", "", "judge", ""));

    /** 演示用的估算单价，可以用 EAB_MODEL_PRICES（JSON）覆盖，不代表任何厂商的实际报价 */
    private static final Map<String, Price> DEFAULT_PRICES = Map.of(
            "scripted", new Price(0, 0),
            "claude-haiku-5-5", new Price(1, 5),
            "claude-sonnet-5-5", new Price(3, 15),
            "claude-opus-5-5", new Price(5, 25));

    public static GatewayConfig scripted() {
        return fromEnv(Map.of());
    }

    public static GatewayConfig fromEnv(Map<String, String> env) {
        String provider = env.getOrDefault("EAB_MODEL_PROVIDER", "scripted");
        if (!PROVIDERS.contains(provider)) {
            throw new ConfigException("EAB_MODEL_PROVIDER 取值应为 " + String.join("、", PROVIDERS) + "，实际为 " + provider);
        }
        Map<String, String> profiles = new LinkedHashMap<>();
        for (String profile : PROFILES) {
            String value = env.get("EAB_MODEL_" + profile.toUpperCase());
            profiles.put(profile, blank(value) ? DEFAULT_PROFILES.get(provider).get(profile) : value);
        }
        int maxTokens = parseMaxTokens(env.get("EAB_MODEL_MAX_TOKENS"));
        int timeoutSeconds = parseInt(env, "EAB_MODEL_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS, 10, 3600);
        int maxRetries = parseInt(env, "EAB_MODEL_MAX_RETRIES", DEFAULT_MAX_RETRIES, 0, 5);
        Map<String, Price> prices = new LinkedHashMap<>(DEFAULT_PRICES);
        prices.putAll(parsePrices(env.get("EAB_MODEL_PRICES")));
        return switch (provider) {
            case "anthropic" ->
                new GatewayConfig(
                        provider,
                        profiles,
                        env.getOrDefault("EAB_ANTHROPIC_BASE_URL", "https://api.anthropic.com"),
                        require(env, "ANTHROPIC_API_KEY"),
                        prices,
                        maxTokens,
                        timeoutSeconds,
                        maxRetries);
            case "openai" -> {
                String apiKey = require(env, "OPENAI_API_KEY");
                String baseUrl = require(env, "EAB_OPENAI_BASE_URL");
                profiles.forEach((profile, model) -> {
                    if (model.isEmpty()) {
                        throw new ConfigException("OpenAI 兼容提供方需要设置 EAB_MODEL_" + profile.toUpperCase());
                    }
                });
                yield new GatewayConfig(
                        provider, profiles, baseUrl, apiKey, prices, maxTokens, timeoutSeconds, maxRetries);
            }
            default -> new GatewayConfig(provider, profiles, null, null, prices, maxTokens, timeoutSeconds, maxRetries);
        };
    }

    static int parseInt(Map<String, String> env, String name, int defaultValue, int min, int max) {
        String value = env.get(name);
        if (blank(value)) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException e) {
            // 落到下面的报错
        }
        throw new ConfigException(name + " 应为 " + min + " 到 " + max + " 之间的整数，实际为 " + value);
    }

    static int parseMaxTokens(String value) {
        if (blank(value)) return DEFAULT_MAX_TOKENS;
        try {
            int parsed = Integer.parseInt(value.strip());
            if (parsed >= MIN_MAX_TOKENS && parsed <= MAX_MAX_TOKENS) return parsed;
        } catch (NumberFormatException e) {
            // 落到下面的报错
        }
        throw new ConfigException(
                "EAB_MODEL_MAX_TOKENS 应为 " + MIN_MAX_TOKENS + " 到 " + MAX_MAX_TOKENS + " 之间的整数，实际为 " + value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String require(Map<String, String> env, String name) {
        String value = env.get(name);
        if (blank(value)) throw new ConfigException("缺少环境变量 " + name);
        return value;
    }

    private static Map<String, Price> parsePrices(String raw) {
        if (blank(raw)) return Map.of();
        try {
            return JsonMapper.builder().build().readValue(raw, new TypeReference<Map<String, Price>>() {});
        } catch (RuntimeException e) {
            throw new ConfigException("EAB_MODEL_PRICES 不是合法的 JSON");
        }
    }

    public String modelFor(String profile) {
        return profiles.getOrDefault(profile, profiles.get("reasoning"));
    }

    /** @param priced 没有单价的模型记为 0 并在追踪里标明 */
    public record Cost(double usd, boolean priced) {}

    public Cost estimateCost(String model, long inputTokens, long outputTokens) {
        Price price = prices.get(model);
        if (price == null) return new Cost(0, false);
        double usd = (inputTokens * price.input() + outputTokens * price.output()) / 1_000_000;
        return new Cost(Math.round(usd * 1_000_000) / 1_000_000.0, true);
    }

    /** 粗略估算 token 数：中文约一字一个 token，其他字符约四个一个 */
    public static int estimateTokens(String text) {
        if (text == null) return 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '㐀' && c <= '鿿') cjk++;
        }
        return cjk + (int) Math.ceil((text.length() - cjk) / 4.0);
    }

    @Override
    public String toString() {
        // 不把 Key 打进日志
        return "GatewayConfig[provider=" + provider + ", profiles=" + profiles + ", baseUrl=" + baseUrl + "]";
    }
}
