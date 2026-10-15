package dev.eab.erp;

import tools.jackson.databind.json.JsonMapper;

/** DemoERP 的 JSON 配置：响应保留 null 字段，与 TS 版一致（例如未交付记录的 actualDate: null） */
public final class ErpJson {

    private ErpJson() {}

    public static final JsonMapper MAPPER = JsonMapper.builder().build();
}
