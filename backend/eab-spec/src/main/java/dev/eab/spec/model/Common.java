package dev.eab.spec.model;

import java.util.List;
import java.util.Map;

/** 各 kind 共用的结构。可选字段缺省时为 null，有默认值的列表缺省时为空列表（由 schema 填充） */
public final class Common {

    private Common() {}

    public record Metadata(
            String name,
            String namespace,
            String version,
            String displayName,
            String description,
            String owner,
            List<String> businessProcess,
            Map<String, String> labels) {}

    /** 路由信息，进入能力目录的场景（主提示词 5.1 节） */
    public record Routing(String description, List<String> examples, boolean direct) {}

    public record Trigger(String type, String event, String cron) {}

    public record EvalsRef(List<String> suites) {}
}
