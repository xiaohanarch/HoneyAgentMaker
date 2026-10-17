package dev.eab.platform.studio;

import dev.eab.platform.registry.AssetRepository.AssetFilter;
import dev.eab.platform.registry.RegistryService;
import dev.eab.runtime.Bm25;
import dev.eab.spec.Viewer;
import dev.eab.spec.model.Asset;
import dev.eab.spec.model.Asset.SemanticPackAsset;
import java.util.ArrayList;
import java.util.List;

/**
 * 提交扩展需求时按需求文本在本租户可见的语义包里检索相关的业务对象与规则（主提示词 10.3 节“需求自动附带业务上下文”），
 * 各取前 3 条交给开发者，让他不用从头猜口径。检索用 BM25，与统一入口路由的召回同一套实现。
 */
public final class SemanticContexts {

    static final int LIMIT = 3;

    public record Context(List<String> semanticObjects, List<String> rules) {

        public static final Context EMPTY = new Context(List.of(), List.of());

        public boolean isEmpty() {
            return semanticObjects.isEmpty() && rules.isEmpty();
        }
    }

    private SemanticContexts() {}

    public static Context attach(RegistryService registry, String tenantId, String query) {
        if (query == null || query.isBlank()) return Context.EMPTY;
        List<Bm25.Passage> objects = new ArrayList<>();
        List<Bm25.Passage> rules = new ArrayList<>();
        for (Asset asset : registry.latestPublished(AssetFilter.all(), Viewer.tenant(tenantId))
                .assets()) {
            if (!(asset instanceof SemanticPackAsset pack)) continue;
            if (pack.spec().objects() != null) {
                for (var object : pack.spec().objects()) {
                    StringBuilder text = new StringBuilder(object.description() == null ? "" : object.description());
                    if (object.fields() != null) {
                        for (var field : object.fields()) {
                            if (field.description() != null) text.append(" ").append(field.description());
                        }
                    }
                    if (!text.isEmpty()) {
                        objects.add(new Bm25.Passage(object.ref(), object.ref(), text.toString()));
                    }
                }
            }
            if (pack.spec().rules() != null) {
                for (var rule : pack.spec().rules()) {
                    String text = ((rule.description() == null ? "" : rule.description()) + " "
                                    + (rule.remedy() == null ? "" : rule.remedy()))
                            .strip();
                    if (!text.isEmpty()) rules.add(new Bm25.Passage(rule.id(), rule.id(), text));
                }
            }
        }
        return new Context(
                Bm25.search(objects, query, LIMIT).stream()
                        .map(h -> h.passage().source())
                        .toList(),
                Bm25.search(rules, query, LIMIT).stream()
                        .map(h -> h.passage().source())
                        .toList());
    }
}
