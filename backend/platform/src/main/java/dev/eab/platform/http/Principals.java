package dev.eab.platform.http;

import dev.eab.directory.DemoDirectory;
import dev.eab.directory.DemoDirectory.DemoUser;
import dev.eab.platform.identity.TokenService;
import dev.eab.spec.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 从 Bearer 用户令牌得到调用者。身份只来自平台签发的令牌，不信任任何请求头里的自报身份。
 */
public final class Principals {

    /**
     * 已认证的调用者：演示用户及其对应的资产查看者身份。
     *
     * @param token 原始用户令牌，令牌交换时作为 subject_token
     */
    public record Principal(DemoUser user, Viewer viewer, String token) {}

    private final TokenService tokens;

    public Principals(TokenService tokens) {
        this.tokens = tokens;
    }

    /** 厂商研发对应厂商身份，平台运维对应平台身份，其余按租户 */
    public static Viewer viewerOf(DemoUser user) {
        return switch (user.tenantId()) {
            case "vendor" -> Viewer.VENDOR;
            case "platform" -> Viewer.PLATFORM;
            default -> Viewer.tenant(user.tenantId());
        };
    }

    /** 鉴权规则用到的主体事实 */
    public record SubjectFacts(String userId, String tenantId, List<String> roles, List<String> operations) {}

    public static SubjectFacts subjectFacts(DemoUser user) {
        return new SubjectFacts(user.id(), user.tenantId(), user.roles(), DemoDirectory.permittedOperations(user));
    }

    /** 失败时抛 401 */
    public Principal require(HttpServletRequest request) {
        return verify(request.getHeader("Authorization"));
    }

    /** 校验 Authorization 头里的用户令牌，失败时抛 401；MCP 端点等不经过 HttpServletRequest 的入口也用它 */
    public Principal verify(String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(401, "UNAUTHENTICATED", "缺少 Authorization: Bearer <用户令牌>");
        }
        String token = header.substring(7);
        try {
            var claims = tokens.verifyUser(token);
            DemoUser user = DemoDirectory.findUser(claims.sub())
                    .orElseThrow(() -> new TokenService.TokenException("INVALID_TOKEN", "令牌中的用户不存在"));
            return new Principal(user, viewerOf(user), token);
        } catch (TokenService.TokenException e) {
            throw new ApiException(401, "INVALID_TOKEN", e.getMessage());
        }
    }
}
