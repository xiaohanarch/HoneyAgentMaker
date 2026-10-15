package dev.eab.platform.http;

import dev.eab.platform.http.ApiResponse.ApiError;

/** 控制器里抛出，由 ApiExceptionHandler 转成统一信封和对应的 HTTP 状态 */
public final class ApiException extends RuntimeException {

    private final int status;
    private final ApiError error;

    public ApiException(int status, ApiError error) {
        super(error.message());
        this.status = status;
        this.error = error;
    }

    public ApiException(int status, String code, String message) {
        this(status, ApiError.of(code, message));
    }

    public int status() {
        return status;
    }

    public ApiError error() {
        return error;
    }
}
