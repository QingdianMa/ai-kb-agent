package com.kbagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbagent.config.AppProperties;
import com.kbagent.retrieval.RetrievalOptions;
import com.kbagent.retrieval.RetrievalService;
import com.kbagent.retrieval.RetrievedChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评测执行器：把同一套评测集跑过四种检索配置，输出对比数据。
 *
 * 这是一次"消融实验"（ablation study）：把混合检索和重排分别打开、关闭，
 * 就能量化出每一层各自的贡献，而不是笼统地说"我加了混合检索，效果变好了"。
 *
 * 指标定义：
 *   Coverage@K —— 召回的 K 个片段里，覆盖了题目所要求关键词的比例（对所有题取平均）
 *   HitRate@K  —— 关键词被全部命中的题目占比
 * 两者都按题目类别分组统计，这样能看出关键词通道到底在哪类问题上起作用。
 */
@Service
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final String OVERALL = "全部";

    private final RetrievalService retrievalService;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public EvalRunner(RetrievalService retrievalService,
                      AppProperties properties,
                      ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> run(String pathString) {
        EvalSet set = load(pathString);
        int topK = properties.getRetrieval().getMaxResults();
        log.info("[评测] 评测集《{}》共 {} 题，召回 {} 条", set.name(), set.items().size(), topK);

        List<Map<String, Object>> results = new ArrayList<>();
        for (RetrievalOptions options : RetrievalOptions.ablationSet()) {
            results.add(evaluate(set, options, topK));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("evalSet", set.name());
        response.put("questions", set.items().size());
        response.put("topK", topK);
        response.put("results", results);
        return response;
    }

    private Map<String, Object> evaluate(EvalSet set, RetrievalOptions options, int topK) {
        long startedAt = System.currentTimeMillis();
        int coveredKeywords = 0;
        int totalKeywords = 0;
        int fullyHitQuestions = 0;
        List<Integer> missedQuestions = new ArrayList<>();
        Map<String, CategoryStat> byCategory = new LinkedHashMap<>();

        for (EvalItem item : set.items()) {
            List<RetrievedChunk> hits;
            try {
                hits = retrievalService.retrieve(item.question(), topK, options);
            } catch (Exception e) {
                log.warn("[评测] 第 {} 题检索失败: {}", item.id(), e.getMessage());
                hits = List.of();
            }
            String context = hits.stream().map(RetrievedChunk::text).collect(Collectors.joining("\n"));

            int hitKeywords = 0;
            for (String keyword : item.keywords()) {
                if (context.contains(keyword)) {
                    hitKeywords++;
                }
            }
            coveredKeywords += hitKeywords;
            totalKeywords += item.keywords().size();
            if (hitKeywords == item.keywords().size()) {
                fullyHitQuestions++;
            } else {
                missedQuestions.add(item.id());
            }
            byCategory.computeIfAbsent(item.category() == null ? "未分类" : item.category(),
                    key -> new CategoryStat()).add(hitKeywords, item.keywords().size());
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("config", options.label());
        summary.put("keywordEnabled", options.keywordEnabled());
        summary.put("rerankEnabled", options.rerankEnabled());
        summary.put("coverageAtK", ratio(coveredKeywords, totalKeywords));
        summary.put("hitRateAtK", ratio(fullyHitQuestions, set.items().size()));
        summary.put("missedQuestions", missedQuestions);
        summary.put("elapsedMs", System.currentTimeMillis() - startedAt);

        Map<String, Object> categoryView = new LinkedHashMap<>();
        byCategory.forEach((category, stat) -> {
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("coverageAtK", ratio(stat.covered, stat.total));
            cell.put("hitRateAtK", ratio(stat.fullyHit, stat.questions));
            categoryView.put(category, cell);
        });
        summary.put("byCategory", categoryView);

        log.info("[评测] {} -> Coverage@{}={}  HitRate@{}={}  未完全命中 {} 题  耗时 {} ms",
                options.label(), topK, summary.get("coverageAtK"),
                topK, summary.get("hitRateAtK"), missedQuestions.size(), summary.get("elapsedMs"));
        return summary;
    }

    private double ratio(int numerator, int denominator) {
        if (denominator == 0) {
            return 0;
        }
        return Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    private EvalSet load(String pathString) {
        Path path = Path.of(pathString);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("找不到评测集文件: " + path.toAbsolutePath());
        }
        try {
            return objectMapper.readValue(path.toFile(), EvalSet.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取评测集失败: " + path, e);
        }
    }

    /** 逐类累加的小计数器。 */
    private static class CategoryStat {
        private int covered;
        private int total;
        private int questions;
        private int fullyHit;

        void add(int hitKeywords, int keywordCount) {
            covered += hitKeywords;
            total += keywordCount;
            questions++;
            if (hitKeywords == keywordCount) {
                fullyHit++;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalSet(String name, List<EvalItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvalItem(int id, String question, List<String> keywords, String category) {
    }
}
