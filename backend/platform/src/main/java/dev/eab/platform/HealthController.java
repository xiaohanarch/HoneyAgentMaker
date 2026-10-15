package dev.eab.platform;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查，pnpm demo 和部署脚本据此判断服务就绪；返回结构与 TS 版一致，前端无需改动 */
@Hidden
@RestController
public class HealthController {

    public record HealthReport(String status, String service, String version, long uptimeSeconds) {}

    private final Instant startedAt = Instant.now();

    @GetMapping("/health")
    public HealthReport health() {
        return new HealthReport(
                "ok",
                PlatformApplication.SERVICE_NAME,
                PlatformApplication.SERVICE_VERSION,
                Duration.between(startedAt, Instant.now()).toSeconds());
    }
}
