package dev.eab.erp.seed;

import java.util.List;

/** 种子数据用的虚构名称词库，不对应任何真实企业 */
final class SeedNames {

    private SeedNames() {}

    static final List<String> SUPPLIER_PREFIX_T001 =
            List.of("华", "中", "恒", "瑞", "鑫", "宏", "晨", "联", "盛", "泰", "嘉", "通", "宇", "金", "科", "启");
    static final List<String> SUPPLIER_PREFIX_T002 =
            List.of("航", "星", "远", "达", "海", "云", "新", "博", "天", "昊", "凌", "锐", "弘", "晟", "聚", "恒");
    static final List<String> SUPPLIER_MIDDLE = List.of("达", "泰", "源", "丰", "辉", "邦", "诚", "德", "信", "利", "安", "和");
    static final List<String> SUPPLIER_SUFFIX = List.of("精密", "电子", "机械", "材料", "五金", "塑胶", "科技", "包装", "化工", "模具");

    static final List<String> CITIES_EAST = List.of("苏州", "无锡", "宁波", "常州", "嘉兴", "南通", "昆山", "绍兴");
    static final List<String> CITIES_SOUTH = List.of("东莞", "佛山", "中山", "惠州", "江门", "珠海");
    static final List<String> CITIES_NORTH = List.of("天津", "廊坊", "保定", "唐山");

    static final List<String> MATERIAL_TYPES =
            List.of("轴承", "齿轮", "电机", "传感器", "印制电路板", "连接器", "外壳", "螺栓", "密封圈", "线束");
    static final List<String> MATERIAL_SPECS =
            List.of("A型", "B型", "C型", "精密级", "标准级", "加强型", "微型", "大功率", "耐高温", "防水型");
    static final List<String> UNITS = List.of("件", "个", "套", "米", "千克");

    static final List<String> PAYMENT_TERMS = List.of("月结 30 天", "月结 60 天", "货到付款", "预付 30%，验收后付清");
    static final List<String> PRICE_TERMS = List.of("年度框架价，季度调价", "一单一议", "阶梯价：月采购量超过 5000 件降 3%");

    record AnomalyType(String type, String label) {}

    static final List<AnomalyType> ANOMALY_TYPES = List.of(
            new AnomalyType("duplicate_payment", "重复付款"),
            new AnomalyType("split_invoice", "拆分发票"),
            new AnomalyType("round_amount", "整数金额"),
            new AnomalyType("off_hours", "非工作时间过账"),
            new AnomalyType("reversal", "冲销"));

    /** @param fail 未通过时的阻塞原因；null 表示该项在演示数据里通过 */
    record CloseCheck(String name, String owner, String fail) {}

    static final List<CloseCheck> CLOSE_CHECKS_ASSET_ACCOUNTING = List.of(
            new CloseCheck("本期折旧计提", "资产会计", "两张资产卡片缺少折旧方法，计提未完成"),
            new CloseCheck("在建工程转固确认", "项目会计", "二号厂房改造项目验收单未上传，无法确认转固"),
            new CloseCheck("资产减值测试", "财务经理", "三台闲置设备缺少评估报告"),
            new CloseCheck("资产卡片与总账对账", "资产会计", null),
            new CloseCheck("本期新增资产入账", "资产会计", null),
            new CloseCheck("资产处置损益结转", "总账会计", null),
            new CloseCheck("租赁资产使用权摊销", "总账会计", null),
            new CloseCheck("资产盘点差异处理", "资产管理员", null));
}
