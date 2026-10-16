package dev.eab.platform.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import dev.eab.platform.lifecycle.VersionDiff.Change;
import dev.eab.spec.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class VersionDiffTest {

    static JsonNode json(String s) {
        return Json.MAPPER.readTree(s);
    }

    @Test
    @DisplayName("逐个叶子比较，忽略版本号；数组按下标展开，记录新增、删除和修改")
    void diffsLeaves() {
        var before = json(
                "{\"metadata\":{\"version\":\"1.0.0\"},\"spec\":{\"tools\":[\"a\",\"b\"],\"x\":1,\"gone\":true,\"empty\":{}}}");
        var after = json(
                "{\"metadata\":{\"version\":\"1.1.0\"},\"spec\":{\"tools\":[\"a\",\"c\",\"d\"],\"x\":1,\"empty\":[]}}");
        var changes = VersionDiff.diff(before, after);
        assertThat(changes)
                .extracting(Change::path, Change::change, Change::before, Change::after)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("spec.tools[1]", "changed", "b", "c"),
                        org.assertj.core.groups.Tuple.tuple("spec.gone", "removed", "true", null),
                        org.assertj.core.groups.Tuple.tuple("spec.empty", "changed", "{}", "[]"),
                        org.assertj.core.groups.Tuple.tuple("spec.tools[2]", "added", null, "d"));
        assertThat(changes).noneMatch(Change::redacted);
    }

    @Test
    @DisplayName("对查看者裁剪的内容只显示“已变更”，不给出前后内容和摘要")
    void hidesRedactedContent() {
        var before = json("{\"spec\":{\"instructions\":{\"$redacted\":true,\"digest\":\"aaa\"}}}");
        var after = json("{\"spec\":{\"instructions\":{\"$redacted\":true,\"digest\":\"bbb\"}}}");
        var changes = VersionDiff.diff(before, after);
        assertThat(changes).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo("spec.instructions");
            assertThat(c.redacted()).isTrue();
            assertThat(c.before()).isNull();
            assertThat(c.after()).isEqualTo(VersionDiff.HIDDEN);
        });
        assertThat(changes.toString()).doesNotContain("aaa", "bbb");
        assertThat(VersionDiff.diff(before, before)).isEmpty();
        assertThat(VersionDiff.diff(null, json("{\"a\":1}")))
                .singleElement()
                .satisfies(c -> assertThat(c.change()).isEqualTo("added"));
    }
}
