package dev.eab.platform.runtime;

import dev.eab.platform.PlatformProperties;
import dev.eab.runtime.Bm25;
import dev.eab.runtime.Ports;
import dev.eab.spec.AssetResolver;
import dev.eab.spec.model.Asset.KnowledgeSourceAsset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识源上的文档检索：知识源资产列出文档的标题和路径（相对于 assets 目录），这里读出 Markdown 后用 BM25 检索，
 * 返回片段和出处。路径只能落在 assets 目录内。
 */
final class KnowledgeDocuments implements Ports.Documents {

    private final AssetResolver resolver;
    private final Path root;

    KnowledgeDocuments(AssetResolver resolver) {
        this(resolver, PlatformProperties.repoRoot().resolve("assets"));
    }

    KnowledgeDocuments(AssetResolver resolver, Path root) {
        this.resolver = resolver;
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public List<Map<String, Object>> search(String knowledgeSource, String query, int limit) {
        KnowledgeSourceAsset source = resolver.resolveOfKind(knowledgeSource, KnowledgeSourceAsset.class, AssetResolver.PUBLISHED)
                .orElseThrow(() -> new IllegalStateException("找不到知识源 " + knowledgeSource));
        List<Bm25.Passage> passages = new ArrayList<>();
        for (var document : source.spec().documents()) {
            Path file = root.resolve(document.path()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
            try {
                passages.addAll(Bm25.split(document.path(), document.title(), Files.readString(file)));
            } catch (IOException e) {
                throw new IllegalStateException("读取文档 " + document.title() + " 失败：" + e.getMessage(), e);
            }
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Bm25.Hit hit : Bm25.search(passages, query, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", hit.passage().title());
            item.put("source", hit.passage().source());
            item.put("text", hit.passage().text());
            item.put("score", hit.score());
            hits.add(item);
        }
        return hits;
    }
}
