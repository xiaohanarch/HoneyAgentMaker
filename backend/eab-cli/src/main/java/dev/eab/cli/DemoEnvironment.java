package dev.eab.cli;

import dev.eab.erp.DemoErpApplication;
import dev.eab.platform.PlatformApplication;
import dev.eab.platform.PlatformServices;
import dev.eab.platform.runtime.RuntimeService;
import java.net.ServerSocket;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 进程内的演示环境：平台和 DemoERP 各自监听本机随机端口，内存库，链路与真实部署相同。
 * agent:run、eval:smoke 和 eab 的端到端测试共用这一个环境，不依赖 pnpm demo，断网也能跑。
 */
public final class DemoEnvironment implements AutoCloseable {

    final ConfigurableApplicationContext platform;
    final ConfigurableApplicationContext erp;

    public DemoEnvironment() throws Exception {
        int erpPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            erpPort = socket.getLocalPort();
        }
        String secret = UUID.randomUUID().toString();
        // 两个应用的 application.yml 都在类路径上，这里不读配置文件，全部用参数给出
        platform = new SpringApplicationBuilder(PlatformApplication.class)
                .logStartupInfo(false)
                .run(
                        "--spring.config.name=eab-cli",
                        "--spring.threads.virtual.enabled=true",
                        "--logging.level.root=error",
                        "--spring.main.banner-mode=off",
                        "--server.port=0",
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.ephemeral-keys=true",
                        "--eab.seed-assets=true",
                        "--eab.demo-mode=true",
                        "--eab.internal-secret=" + secret,
                        "--eab.demo-erp-url=http://127.0.0.1:" + erpPort);
        erp = new SpringApplicationBuilder(DemoErpApplication.class)
                .logStartupInfo(false)
                .run(
                        "--spring.config.name=eab-cli",
                        "--spring.threads.virtual.enabled=true",
                        "--logging.level.root=error",
                        "--spring.main.banner-mode=off",
                        "--server.port=" + erpPort,
                        "--server.address=127.0.0.1",
                        "--eab.db-path=:memory:",
                        "--eab.seed-data=true",
                        "--eab.platform-url=" + platformUrl(),
                        "--eab.internal-secret=" + secret,
                        "--eab.delegation-cache-millis=0");
    }

    /** 平台在本机的访问地址（随机端口） */
    public String platformUrl() {
        return "http://127.0.0.1:" + platform.getEnvironment().getProperty("local.server.port");
    }

    public PlatformServices services() {
        return platform.getBean(PlatformServices.class);
    }

    public RuntimeService runtime() {
        return platform.getBean(RuntimeService.class);
    }

    @Override
    public void close() {
        erp.close();
        platform.close();
    }
}
