package com.kbagent.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbagent.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排通道：用交叉编码器对候选片段做精排。
 *
 * 为什么需要它：向量检索是"双塔"结构，问题和文档分别编码后算相似度，精度有天花板。
 * 重排模型把问题和文档一起送进模型打分（交叉编码），精度高得多，代价是慢，
 * 所以只对几十条候选跑，不用于全库检索。
 *
 * 容错设计：调用失败时原样返回候选，不让重排拖垮整条链路。
 */
@Component
public class RerankClient {

    private static final Logger log = LoggerFactory.getLogger(RerankClient.class);
    private static final int MAX_LOG_CHARS = 200;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RerankClient(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        try {
            String baseUrl = properties.getEmbedding().getBaseUrl().replaceAll("/+$", "");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getRerank().getModel());
            body.put("query", query);
            body.put("documents", candidates.stream().map(RetrievedChunk::text).toList());
            body.put("top_n", candidates.size());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/rerank"))
                    .timeout(Duration.ofSeconds(properties.getRerank().getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getEmbedding().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("[重排] 接口返回 {}，本次跳过重排：{}",
                        response.statusCode(), truncate(response.body()));
                return candidates;
            }

            JsonNode results = objectMapper.readTree(response.body()).path("results");
            List<RetrievedChunk> ordered = new ArrayList<>(results.size());
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index >= 0 && index < candidates.size()) {
                    ordered.add(candidates.get(index).withScore(item.path("relevance_score").asDouble(0)));
                }
            }
            log.info("[重排] {} 条候选精排完成，最高分 {}", candidates.size(),
                    ordered.isEmpty() ? "-" : String.format("%.4f", ordered.get(0).score()));
            return ordered.isEmpty() ? candidates : ordered;
        } catch (Exception e) {
            log.warn("[重排] 调用失败，本次跳过重排：{}", e.getMessage());
            return candidates;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOG_CHARS ? text : text.substring(0, MAX_LOG_CHARS);
    }
}
