package dev.eab.erp.events;

import dev.eab.erp.ErpServices;
import dev.eab.erp.seed.Seeder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 演示控制台的「重置」在 DemoERP 侧的部分：清空运行期数据并按今天重新生成业务数据，
 * 等价 pnpm seed:reset 的 DemoERP 部分。只接受带内部调用密钥的请求（经平台转发）。
 */
@RestController
public class DemoResetController {

    private final ErpServices services;

    public DemoResetController(ErpServices services) {
        this.services = services;
    }

    private boolean isInternal(String header) {
        String secret = services.internalSecret();
        return header != null
                && secret != null
                && MessageDigest.isEqual(
                        header.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/demo/v1/reset")
    public ResponseEntity<Object> reset(@RequestHeader(value = "x-eab-internal-token", required = false) String token) {
        if (!isInternal(token)) {
            return ResponseEntity.status(401)
                    .body(Map.of(
                            "error",
                            Map.of("code", "INTERNAL_ONLY", "reason", "只接受内部调用", "suggestion", "通过平台的演示控制台重置")));
        }
        Seeder.seed(services.db(), services.clock().instant());
        return ResponseEntity.status(202).body(Map.of("accepted", true));
    }
}
