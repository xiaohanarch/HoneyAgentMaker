package dev.eab.platform.registry;

import dev.eab.spec.AssetParser.AssetIssue;
import java.util.List;

/** 注册中心操作的结果：成功带值，失败带错误编码、说明和问题列表 */
public sealed interface RegistryResult<T> {

    /** 错误编码与 HTTP 状态的对应在 RegistryController 里 */
    enum ErrorCode {
        ASSET_INVALID,
        REGISTRY_WRITE_FORBIDDEN,
        VERSION_EXISTS,
        NOT_FOUND,
        INVALID_TRANSITION,
        INTEGRITY_VIOLATION,
        PUBLISH_BLOCKED
    }

    record RegistryError(ErrorCode code, String message, List<AssetIssue> issues) {}

    record Ok<T>(T value) implements RegistryResult<T> {}

    record Err<T>(RegistryError error) implements RegistryResult<T> {}

    static <T> RegistryResult<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> RegistryResult<T> fail(ErrorCode code, String message) {
        return new Err<>(new RegistryError(code, message, null));
    }

    static <T> RegistryResult<T> fail(ErrorCode code, String message, List<AssetIssue> issues) {
        return new Err<>(new RegistryError(code, message, issues));
    }

    default boolean ok() {
        return this instanceof Ok;
    }

    default T value() {
        if (this instanceof Ok<T> success) return success.value();
        throw new IllegalStateException("注册中心操作失败：" + error().message());
    }

    default RegistryError error() {
        return this instanceof Err<T> failure ? failure.error() : null;
    }
}
