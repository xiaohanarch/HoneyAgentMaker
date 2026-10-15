package dev.eab.spec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资产引用：命名空间/名称[@版本范围]。版本范围用 semver 语法，省略时表示“当前生效的最新版本”。
 *
 * @param range 版本范围，没有时为 null
 */
public record AssetRef(String namespace, String name, String range) {

    public static final class InvalidAssetRefException extends IllegalArgumentException {
        public InvalidAssetRefException(String ref, String reason) {
            super("资产引用 \"" + ref + "\" 不合法：" + reason);
        }
    }

    private static final Pattern REF = Pattern.compile("^([a-z0-9][a-z0-9.-]*)/([a-z0-9][a-z0-9-]*)(?:@(.+))?$");

    public static AssetRef parse(String ref) {
        Matcher match = REF.matcher(ref);
        if (!match.matches()) throw new InvalidAssetRefException(ref, "格式应为 命名空间/名称[@版本范围]");
        String namespace = match.group(1);
        try {
            Layers.layerOfNamespace(namespace);
        } catch (Layers.InvalidNamespaceException e) {
            throw new InvalidAssetRefException(ref, e.getMessage());
        }
        String range = match.group(3);
        if (range != null && !Versions.isValidRange(range)) {
            throw new InvalidAssetRefException(ref, "版本范围 \"" + range + "\" 不是合法的 semver 范围");
        }
        return new AssetRef(namespace, match.group(2), range);
    }

    public static boolean isValid(String ref) {
        try {
            parse(ref);
            return true;
        } catch (InvalidAssetRefException e) {
            return false;
        }
    }

    /** 锁定到具体版本的引用，例如 vendor.pur/procurement@1.0.0 */
    public static boolean isPinned(String ref) {
        try {
            AssetRef parsed = parse(ref);
            return parsed.range() != null && Versions.isValid(parsed.range());
        } catch (InvalidAssetRefException e) {
            return false;
        }
    }

    /** 不带版本的资产标识，用作注册中心的键 */
    public static String key(String namespace, String name) {
        return namespace + "/" + name;
    }

    public String key() {
        return key(namespace, name);
    }

    @Override
    public String toString() {
        return range == null ? key() : key() + "@" + range;
    }
}
