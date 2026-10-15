package dev.eab.platform.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.eab.platform.TestPlatform;
import dev.eab.platform.identity.TokenService;
import dev.eab.spec.Json;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningTest {

    @Test
    @DisplayName("规范化 JSON：键按 UTF-16 排序，数字按 ECMAScript 格式，与字段顺序无关")
    void canonicalJson() {
        var a = Json.MAPPER.readTree("{\"b\":1,\"a\":[0.5,1.0,1e21,1e-7],\"c\":\"换行\\n\"}");
        assertThat(CanonicalJson.of(a)).isEqualTo("{\"a\":[0.5,1,1e+21,1e-7],\"b\":1,\"c\":\"换行\\n\"}");
        var b = Json.MAPPER.readTree("{\"c\":\"换行\\n\",\"a\":[0.5,1.0,1e21,1e-7],\"b\":1}");
        assertThat(CanonicalJson.hashOf(CanonicalJson.of(a))).isEqualTo(CanonicalJson.hashOf(CanonicalJson.of(b)));
    }

    @Test
    @DisplayName("签名绑定载荷，换载荷或换密钥都验签失败；摘要带密钥")
    void signatures() {
        Ed25519Signer signer = Ed25519Signer.ephemeral();
        String payload = Signer.signingPayload("vendor.fin/x@1.0.0", "sha256:00");
        String signature = signer.sign(payload);
        assertThat(signer.verify(payload, signature)).isTrue();
        assertThat(signer.verify(Signer.signingPayload("vendor.fin/x@1.1.0", "sha256:00"), signature))
                .isFalse();
        assertThat(Ed25519Signer.ephemeral().verify(payload, signature)).isFalse();
        assertThat(signer.digest("原文"))
                .hasSize(16)
                .isNotEqualTo(Ed25519Signer.ephemeral().digest("原文"));
    }

    @Test
    @DisplayName("密钥文件首次生成，再次读取得到同一把密钥")
    void keysPersist(@TempDir Path dir) {
        Ed25519Signer first = Ed25519Signer.fromDirectory(dir);
        Ed25519Signer second = Ed25519Signer.fromDirectory(dir);
        assertThat(second.verify("x", first.sign("x"))).isTrue();
    }

    @Test
    @DisplayName("令牌：校验受众、种类和有效期，用注入的时钟计算过期")
    void tokens() {
        var clock = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneOffset.UTC);
        var tokens = new TokenService(TestPlatform.IDENTITY_KEYS, clock);
        String user = tokens.issueUser(new TokenService.UserClaims("zhangmin", "t001", List.of("purchasing-manager")));
        assertThat(tokens.verifyUser(user).sub()).isEqualTo("zhangmin");
        assertThatThrownBy(() -> tokens.verifyAgent(user)).hasMessageContaining("受众不符");

        var later = new TokenService(TestPlatform.IDENTITY_KEYS, Clock.offset(clock, java.time.Duration.ofHours(9)));
        assertThatThrownBy(() -> later.verifyUser(user)).hasMessageContaining("已过期");
        assertThat(tokens.jwks().toString()).contains("Ed25519").doesNotContain("\"d\"");
    }
}
