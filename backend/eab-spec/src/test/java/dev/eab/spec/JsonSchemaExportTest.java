package dev.eab.spec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.spec.testing.RepoPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 生成物与 schema 同步检查。修改 schema 后运行 pnpm spec:json-schema，
 * 它以 -Deab.updateSchemas=true 运行本测试，把生成结果写回 schemas/ 目录。
 */
class JsonSchemaExportTest {

    static final Path DIR = RepoPaths.root().resolve("schemas");

    @ParameterizedTest(name = "{0} 的生成物与 schema 同步（不同步时运行 pnpm spec:json-schema）")
    @EnumSource(AssetKind.class)
    void committedSchemaIsInSync(AssetKind kind) throws IOException {
        String generated = JsonSchemaExport.serialize(JsonSchemaExport.kindJsonSchema(kind));
        Path file = DIR.resolve(JsonSchemaExport.fileName(kind));
        if (Boolean.getBoolean("eab.updateSchemas")) {
            Files.createDirectories(DIR);
            Files.writeString(file, generated, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(generated);
    }

    @Test
    @DisplayName("Agent 的 schema 要求 spec 必填字段，步骤的递归定义放在 $defs")
    void agentSchemaRequiresFields() {
        var agent = JsonSchemaExport.kindJsonSchema(AssetKind.Agent);
        var required = agent.get("properties").get("spec").get("required");
        assertThat(required.toString()).contains("expertise", "instructions", "model", "writePolicy", "routing");
        var skill = JsonSchemaExport.kindJsonSchema(AssetKind.Skill);
        assertThat(skill.get("$defs").has("Step")).isTrue();
    }
}
