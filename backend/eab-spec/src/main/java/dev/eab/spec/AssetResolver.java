package dev.eab.spec;

import dev.eab.spec.model.Asset;
import java.util.List;
import java.util.Optional;

/**
 * 按命名空间、名称、版本取资产的只读接口。组装器、静态检查、注册中心共用。
 * 签名校验等完整性检查由实现方负责：返回的资产必须是可信的。
 */
public interface AssetResolver {

    /** 资产生命周期状态（主提示词第 12 节） */
    enum Status {
        draft,
        testing,
        published,
        retired
    }

    record VersionInfo(String version, Status status) {}

    /** 按版本从新到旧排列 */
    List<VersionInfo> listVersions(String namespace, String name);

    Optional<Asset> get(String namespace, String name, String version);

    List<Status> PUBLISHED = List.of(Status.published);

    /** 静态检查时认为“存在”的状态：除已下线外都算 */
    List<Status> LIVE = List.of(Status.draft, Status.testing, Status.published);

    /** 在指定状态的版本里，取满足版本范围的最高版本号 */
    default Optional<String> resolveVersion(AssetRef ref, List<Status> statuses) {
        List<String> candidates = listVersions(ref.namespace(), ref.name()).stream()
                .filter(v -> statuses.contains(v.status()))
                .map(VersionInfo::version)
                .toList();
        return Versions.maxSatisfying(candidates, ref.range() == null ? "*" : ref.range());
    }

    default Optional<Asset> resolve(AssetRef ref, List<Status> statuses) {
        return resolveVersion(ref, statuses).flatMap(v -> get(ref.namespace(), ref.name(), v));
    }

    default Optional<Asset> resolve(String ref, List<Status> statuses) {
        return resolve(AssetRef.parse(ref), statuses);
    }

    default Optional<Asset> resolve(String ref) {
        return resolve(ref, PUBLISHED);
    }

    /** 解析并要求是指定类型，类型不符时返回空 */
    default <T extends Asset> Optional<T> resolveOfKind(String ref, Class<T> type, List<Status> statuses) {
        return resolve(ref, statuses).filter(type::isInstance).map(type::cast);
    }
}
