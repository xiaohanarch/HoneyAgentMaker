package dev.eab.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 模拟 ERP：业务对象 API、开放 API、元数据服务、页面与业务事件。
 * 端口与监听地址由 EAB_DEMO_ERP_PORT、EAB_HOST 指定（见 application.yml）。
 */
@SpringBootApplication
public class DemoErpApplication {

    public static final String SERVICE_NAME = "demo-erp";
    public static final String SERVICE_VERSION = "0.2.0";

    public static void main(String[] args) {
        SpringApplication.run(DemoErpApplication.class, args);
    }
}
