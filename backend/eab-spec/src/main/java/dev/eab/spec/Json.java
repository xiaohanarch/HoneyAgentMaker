package dev.eab.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * 资产模块统一使用的 Jackson 配置：序列化时省略 null（可选字段缺省就不出现），
 * 反序列化时遇到记录类型里没有的字段直接报错，schema 与记录类型不一致能被测试发现。
 */
public final class Json {

    private Json() {}

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public static final YAMLMapper YAML = YAMLMapper.builder().build();

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T fromTree(JsonNode node, Class<T> type) {
        return MAPPER.treeToValue(node, type);
    }
}
