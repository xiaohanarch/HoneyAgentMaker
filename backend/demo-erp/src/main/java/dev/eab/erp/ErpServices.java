package dev.eab.erp;

import dev.eab.erp.bo.BoApi;
import dev.eab.erp.bo.BoRoute;
import dev.eab.erp.bo.InventoryFinanceRoutes;
import dev.eab.erp.bo.PurchasingRoutes;
import dev.eab.erp.db.ErpDb;
import dev.eab.erp.link.PlatformLink;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * DemoERP 的服务对象（对应 TS 版 buildApp 的依赖）：数据库、平台连接、时钟、内部调用密钥和 BO API。
 * Spring 配置和测试都从这里构造，测试不启动 Spring 容器。
 */
public record ErpServices(ErpDb db, PlatformLink link, Clock clock, String internalSecret, BoApi bo) {

    public static ErpServices create(ErpDb db, PlatformLink link, Clock clock, String internalSecret) {
        List<BoRoute> routes = new ArrayList<>(new PurchasingRoutes(db, clock).routes());
        routes.addAll(new InventoryFinanceRoutes(db, clock).routes());
        return new ErpServices(db, link, clock, internalSecret, new BoApi(db, link, clock, routes));
    }
}
