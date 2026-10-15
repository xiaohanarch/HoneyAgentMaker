package dev.eab.platform.http;

import java.util.List;

/** 统一的响应信封：成功时带 data，失败时带 error（与 TS 版、前端约定一致） */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    /**
     * @param details 附加信息，例如组装失败的问题列表
     * @param issues 资产校验问题，带字段路径和行号
     */
    public record ApiError(String code, String message, Object details, List<?> issues) {

        public static ApiError of(String code, String message) {
            return new ApiError(code, message, null, null);
        }
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}
