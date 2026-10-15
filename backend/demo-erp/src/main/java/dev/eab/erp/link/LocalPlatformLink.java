package dev.eab.erp.link;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 本地连接：本地 JWKS、可控的委托状态、记录推送的事件。测试使用 */
public final class LocalPlatformLink implements PlatformLink {

    public record Published(String type, String tenantId, Map<String, Object> payload) {}

    private final TokenVerifier verifier;
    private final Set<String> activeDelegations = ConcurrentHashMap.newKeySet();
    private final List<Published> published = new CopyOnWriteArrayList<>();

    public LocalPlatformLink(JWKSet jwks) {
        this.verifier = new TokenVerifier(new ImmutableJWKSet<SecurityContext>(jwks));
    }

    @Override
    public Map<String, Object> verify(String token, String audience) {
        return verifier.verify(token, audience);
    }

    @Override
    public boolean delegationActive(String id) {
        return activeDelegations.contains(id);
    }

    @Override
    public void publish(String type, String tenantId, Map<String, Object> payload) {
        published.add(new Published(type, tenantId, payload));
    }

    public Set<String> activeDelegations() {
        return activeDelegations;
    }

    public List<Published> published() {
        return published;
    }
}
