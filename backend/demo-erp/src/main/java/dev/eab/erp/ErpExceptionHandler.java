package dev.eab.erp;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** DemoERP 的错误响应统一为 {error: {code, reason, suggestion}}，不暴露内部异常 */
@RestControllerAdvice
public class ErpExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErpExceptionHandler.class);

    private static ResponseEntity<Object> error(int status, String code, String reason, String suggestion) {
        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of("code", code, "reason", reason, "suggestion", suggestion)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable(HttpMessageNotReadableException e) {
        return error(400, "BAD_REQUEST", "请求参数不合法", "检查参数格式");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Object> notFound(NoResourceFoundException e) {
        return error(404, "NOT_FOUND", "没有这个接口", "查看 /openapi.json");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> unexpected(Exception e) {
        if (e instanceof ErrorResponse response && response.getStatusCode().value() < 500) {
            return error(response.getStatusCode().value(), "BAD_REQUEST", "请求不合法", "检查请求方法和参数");
        }
        LOG.error("未处理的异常", e);
        return error(500, "INTERNAL_ERROR", "服务内部错误", "稍后重试");
    }
}
