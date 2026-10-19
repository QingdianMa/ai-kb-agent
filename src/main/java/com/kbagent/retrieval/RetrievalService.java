package com.kbagent.retrieval;

import com.kbagent.config.AppProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第一周先做纯向量召回，把链路跑通、把指标测出来。
 * 第二周再叠加关键词召回 + 融合 + 重排，用同一套评测集对比效果。
 */
@Service
public class RetrievalService {

    private final QueryEmbeddingCache queryEmbeddingCache;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final KeywordIndex keywordIndex;
    private final RerankClient rerankClient;
    private final AppProperties properties;

    public RetrievalService(QueryEmbeddingCache queryEmbeddingCache,
                            EmbeddingStore<TextSegment> embeddingStore,
                            KeywordIndex keywordIndex,
                            RerankClient rerankClient,
                            AppProperties properties) {
        this.queryEmbeddingCache = queryEmbeddingCache;
        this.embeddingStore = embeddingStore;
        this.keywordIndex = keywordIndex;
        this.rerankClient = rerankClient;
        this.properties = properties;
    }

    /** 默认检索管线，供正常问答使用，开关由配置决定。 */
    public List<RetrievedChunk> retrieve(String query, Integer topK) {
        RetrievalOptions options = new RetrievalOptions(
                Boolean.TRUE.equals(properties.getRetrieval().getKeywordEnabled()),
                Boolean.TRUE.equals(properties.getRetrieval().getRerankEnabled()));
        return retrieve(query, topK, options);
    }

    /**
     * 完整管线：向量召回 + 关键词召回 → RRF 融合 → 重排 → 取前 N 条。
     * 每一步都可以用 RetrievalOptions 单独开关，这样就能做消融实验。
     */
    public List<RetrievedChunk> retrieve(String query, Integer topK, RetrievalOptions options) {
        int maxResults = topK != null && topK > 0 ? topK : properties.getRetrieval().getMaxResults();

        List<RetrievedChunk> vectorHits = vectorSearch(query);
        List<RetrievedChunk> candidates = vectorHits;

        if (options.keywordEnabled()) {
            List<RetrievedChunk> keywordHits = keywordIndex.search(
                    query, properties.getRetrieval().getKeywordTopK());
            candidates = fuse(vectorHits, keywordHits, properties.getRetrieval().getFusionTopK());
        }
        if (options.rerankEnabled()) {
            candidates = rerankClient.rerank(query, candidates);
        }
        return candidates.stream().limit(maxResults).toList();
    }

    private List<RetrievedChunk> vectorSearch(String query) {
        // 走缓存：热点问题的向量不用重复计算
        Embedding queryEmbedding = queryEmbeddingCache.embed(query);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(properties.getRetrieval().getVectorTopK())
                .minScore(properties.getRetrieval().getMinScore())
                .build();

        return embeddingStore.search(request).matches().stream()
                .map(match -> new RetrievedChunk(
                        match.embeddingId(),
                        match.embedded().text(),
                        match.embedded().metadata().getString("file_name"),
                        match.embedded().metadata().getString("section"),
                        parseInt(match.embedded().metadata().getString("chunk_index")),
                        match.score()))
                .toList();
    }

    /**
     * RRF（Reciprocal Rank Fusion）融合两路结果。
     *
     * 公式：score = Σ 1 / (k + 排名)，k 取 60。
     * 它只看排名不看分数，所以不用操心"向量的余弦相似度"和"关键词的 BM25 分"
     * 量纲不同的问题——这也是 RRF 在工业界被广泛使用的原因。
     * 一个片段如果两路都召回了，分数自然叠加，排名会往上升。
     */
    private List<RetrievedChunk> fuse(List<RetrievedChunk> first,
                                      List<RetrievedChunk> second,
                                      int topK) {
        int k = properties.getRetrieval().getRrfK();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievedChunk> byId = new LinkedHashMap<>();
        accumulate(scores, byId, first, k);
        accumulate(scores, byId, second, k);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> byId.get(entry.getKey()))
                .toList();
    }

    private void accumulate(Map<String, Double> scores,
                            Map<String, RetrievedChunk> byId,
                            List<RetrievedChunk> hits,
                            int k) {
        for (int rank = 0; rank < hits.size(); rank++) {
            RetrievedChunk hit = hits.get(rank);
            scores.merge(hit.embeddingId(), 1.0 / (k + rank + 1), Double::sum);
            byId.putIfAbsent(hit.embeddingId(), hit);
        }
    }

    private int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
