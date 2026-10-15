package dev.eab.erp;

import dev.eab.erp.bo.OpenApiDocs;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 全部对外接口的 OpenAPI 描述（主提示词第 14 节） */
@RestController
public class OpenApiController {

    private final ErpServices services;

    public OpenApiController(ErpServices services) {
        this.services = services;
    }

    @GetMapping("/openapi.json")
    public Map<String, Object> openApi() {
        return OpenApiDocs.document(services.bo().routes(), false);
    }
}
