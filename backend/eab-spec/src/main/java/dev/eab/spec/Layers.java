package dev.eab.spec;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 三层资产的命名空间约定（主提示词 5.2 节）：
 *
 * <ul>
 *   <li>platform：平台层
 *   <li>vendor.&lt;domain&gt;[...]：厂商标品层
 *   <li>tenant.&lt;tenantId&gt;[...]：租户层
 * </ul>
 */
public final class Layers {

    public enum Layer {
        platform,
        vendor,
        tenant
    }

    public static final class InvalidNamespaceException extends IllegalArgumentException {
        public InvalidNamespaceException(String namespace, String reason) {
            super("命名空间 \"" + namespace + "\" 不合法：" + reason);
        }
    }

    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private Layers() {}

    private static List<String> split(String namespace) {
        List<String> segments = List.of(namespace.split("\\.", -1));
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new InvalidNamespaceException(namespace, "片段 \"" + segment + "\" 只能包含小写字母、数字和连字符");
            }
        }
        return segments;
    }

    public static Layer layerOfNamespace(String namespace) {
        List<String> segments = split(namespace);
        String root = segments.getFirst();
        int rest = segments.size() - 1;
        return switch (root) {
            case "platform" -> {
                if (rest > 0) throw new InvalidNamespaceException(namespace, "平台层命名空间只能是 platform");
                yield Layer.platform;
            }
            case "vendor" -> {
                if (rest == 0) throw new InvalidNamespaceException(namespace, "厂商层需要至少一级子命名空间，例如 vendor.pur");
                yield Layer.vendor;
            }
            case "tenant" -> {
                if (rest == 0) throw new InvalidNamespaceException(namespace, "租户层需要带租户编号，例如 tenant.t001");
                yield Layer.tenant;
            }
            default -> throw new InvalidNamespaceException(namespace, "必须以 platform、vendor 或 tenant 开头");
        };
    }

    public static boolean isValidNamespace(String namespace) {
        try {
            layerOfNamespace(namespace);
            return true;
        } catch (InvalidNamespaceException e) {
            return false;
        }
    }

    /** 租户层命名空间返回租户编号，其他层返回空 */
    public static Optional<String> tenantIdOfNamespace(String namespace) {
        return layerOfNamespace(namespace) == Layer.tenant ? Optional.of(split(namespace).get(1)) : Optional.empty();
    }
}
