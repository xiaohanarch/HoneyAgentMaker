package dev.eab.erp.bo;

import dev.eab.erp.ErpServices;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** BO API 的 HTTP 入口：/bo/v1/** 全部交给 BoApi 调度，OpenAPI 描述单独提供 */
@RestController
public class BoController {

    private final BoApi bo;

    public BoController(ErpServices services) {
        this.bo = services.bo();
    }

    @GetMapping("/bo/v1/openapi.json")
    public Map<String, Object> openApi() {
        return OpenApiDocs.document(bo.routes(), true);
    }

    @RequestMapping("/bo/v1/**")
    public ResponseEntity<Object> dispatch(HttpServletRequest request) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (values.length > 0) query.put(name, values[0]);
        });
        // 请求体只用于 POST 的业务参数；getParameterMap 不读取 JSON 请求体
        byte[] body = request.getInputStream().readAllBytes();
        var response = bo.handle(new BoApi.Request(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                query,
                body,
                request::getHeader));
        var builder = ResponseEntity.status(response.status());
        response.headers().forEach(builder::header);
        return builder.body(response.body());
    }
}
