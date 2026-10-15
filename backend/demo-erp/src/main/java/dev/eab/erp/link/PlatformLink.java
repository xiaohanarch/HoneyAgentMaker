package dev.eab.erp.link;

import java.util.Map;

/**
 * DemoERP 与平台之间的连接：用平台的 JWKS 校验令牌、向平台核实委托、把事件推送给平台。
 * 校验和核实失败都按拒绝处理；推送失败只记日志，不影响业务请求。
 */
public interface PlatformLink {

    /** 校验签名、签发者、受众和有效期，失败时抛异常；返回令牌的全部声明 */
    Map<String, Object> verify(String token, String audience);

    /** 核实不了就当作无效 */
    boolean delegationActive(String id);

    void publish(String type, String tenantId, Map<String, Object> payload);
}
