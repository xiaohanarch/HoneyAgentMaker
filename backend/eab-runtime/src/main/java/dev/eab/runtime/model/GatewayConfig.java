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
 */
public record GatewayConfig(
        String provider, Map<String, String> profiles, String baseUrl, String apiKey, Map<String, Price> prices) {

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
        Map<String, Price> prices = new LinkedHashMap<>(DEFAULT_PRICES);
        prices.putAll(parsePrices(env.get("EAB_MODEL_PRICES")));
        return switch (provider) {
            case "anthropic" ->
                new GatewayConfig(
                        provider,
                        profiles,
                        env.getOrDefault("EAB_ANTHROPIC_BASE_URL", "https://api.anthropic.com"),
                        require(env, "ANTHROPIC_API_KEY"),
                        prices);
            case "openai" -> {
                String apiKey = require(env, "OPENAI_API_KEY");
                String baseUrl = require(env, "EAB_OPENAI_BASE_URL");
                profiles.forEach((profile, model) -> {
                    if (model.isEmpty()) {
                        throw new ConfigException("OpenAI 兼容提供方需要设置 EAB_MODEL_" + profile.toUpperCase());
                    }
                });
                yield new GatewayConfig(provider, profiles, baseUrl, apiKey, prices);
            }
            default -> new GatewayConfig(provider, profiles, null, null, prices);
        };
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
