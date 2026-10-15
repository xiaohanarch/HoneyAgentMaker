package dev.eab.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 平台主服务：资产注册与组装、路由、Agent 运行时、各类网关、治理中心 API、模拟身份服务。
 * 端口与监听地址由 EAB_PLATFORM_PORT、EAB_HOST 指定（见 application.yml）。
 */
@SpringBootApplication
public class PlatformApplication {

    public static final String SERVICE_NAME = "platform";
    public static final String SERVICE_VERSION = "0.1.0";

    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
