package dev.eab.spec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 查看者身份：platform 是平台管理员，vendor 是厂商研发，tenant 是某个租户的用户或开发者。
 * 身份来自平台签发的令牌，不信任请求头。
 */
public sealed interface Viewer {

    record Platform() implements Viewer {}

    record Vendor() implements Viewer {}

    record Tenant(String tenantId) implements Viewer {}

    Viewer PLATFORM = new Platform();
    Viewer VENDOR = new Vendor();

    static Viewer tenant(String tenantId) {
        return new Tenant(tenantId);
    }

    Pattern TENANT = Pattern.compile("^tenant:([a-z0-9][a-z0-9-]*)$");

    static Viewer parse(String value) {
        if (value.equals("platform")) return PLATFORM;
        if (value.equals("vendor")) return VENDOR;
        Matcher match = TENANT.matcher(value);
        if (match.matches()) return new Tenant(match.group(1));
        throw new IllegalArgumentException("无法识别的查看者身份 \"" + value + "\"，应为 platform、vendor 或 tenant:<租户编号>");
    }

    default String format() {
        return switch (this) {
            case Platform p -> "platform";
            case Vendor v -> "vendor";
            case Tenant t -> "tenant:" + t.tenantId();
        };
    }
}
