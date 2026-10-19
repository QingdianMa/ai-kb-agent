package com.kbagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbagent.config.AppProperties;
import com.kbagent.ingest.ContentSectioner;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 反向生成评测集：拿文档片段让模型出题。
 * <p>
 * 为什么需要它：人工从零出 60 道题要几个小时，而且容易只覆盖自己熟悉的部分。
 * 反向生成保证覆盖全面，代价是问题的"提问方式"偏文档中心，所以最终要人工审核、并混入手工题。
 * <p>
 * 关键设计：模型给出的关键词会**回头去原文里校验**，原文里找不到的自动丢弃。
 * 这一步是对模型输出的硬约束——没有它，评测集里会出现"答案根本不在文档里"的脏题。
 */
@Service
public class EvalSetGenerator {

    private static final Logger log = LoggerFactory.getLogger(EvalSetGenerator.class);
    private static final int MAX_SEGMENT_CHARS = 800;
    private static final int SEGMENT_OVERLAP_CHARS = 120;
    private static final int MIN_CHUNK_CHARS = 200;
    /** 关键词长度上限。太长的关键词会跨片段被切断，永远匹配不上。 */
    private static final int MAX_KEYWORD_CHARS = 12;
    /**
     * 关键词数量下限。
     * 只有一个关键词的题目，任何提到这个词的片段都会"命中"，会把评测分数抬高，
     * 所以少于两个关键词的条目直接丢弃。
     */
    private static final int MIN_KEYWORD_COUNT = 2;
    /**
     * 重复判定阈值，用的是"包含度"而不是 Jaccard：
     * 当一条问题几乎是另一条的复述（子集关系）时，Jaccard 会偏小导致漏判，
     * 包含度 = 交集 / 较短者，对这种情况更敏感。
     */
    private static final double DUPLICATE_THRESHOLD = 0.8;

    private static final String PROMPT_TEMPLATE = """
            你是一名测试工程师，正在为一个检索系统构建评测集。

            下面是一段技术文档的内容。请你完成两件事：
            1. 提出一个「真实用户可能会问」的问题，答案必须完全来自这段内容
            2. 从这段内容中挑出 2 到 4 个关键词，它们必须原样出现在内容里

            要求：
            - 问题要口语化，像用户在搜索框里输入的那样，不要照抄小标题
            - 关键词要**短**，每个不超过 10 个字，优先用单个词或标识符
              （例如 minScore、kb_chunk、1024、SseEmitter），不要把整句话当关键词
            - 只针对文档里的**技术内容**提问。不要问"这份文档讲了什么""我该查哪份文档"
              "面试时该怎么回答"这类关于文档本身的问题
            - 只输出一行 JSON，不要解释，不要代码块

            输出格式：
            {"question":"这里写问题","keywords":["关键词1","关键词2"]}

            文档内容：
            ---
            %s
            ---
            """;

    private final ChatModel chatModel;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public EvalSetGenerator(ChatModel chatModel, AppProperties properties, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> generate(Integer limit) {
        int target = limit != null && limit > 0 ? limit : properties.getEval().getGenerateLimit();
        Path docsDir = Path.of(properties.getIngest().getSourceDir());
        if (!Files.isDirectory(docsDir)) {
            throw new IllegalStateException("找不到知识库目录: " + docsDir.toAbsolutePath());
        }

        List<Chunk> all = splitAll(docsDir);
        List<Chunk> sampled = sample(all, target);
        log.info("[评测集生成] 文档切片 {} 个，抽样 {} 条用于出题", all.size(), sampled.size());

        List<Map<String, Object>> items = new ArrayList<>();
        int skipped = 0;
        for (int i = 0; i < sampled.size(); i++) {
            try {
                Map<String, Object> item = generateOne(sampled.get(i), items.size() + 1, items);
                if (item == null) {
                    skipped++;
                } else {
                    items.add(item);
                }
            } catch (Exception e) {
                skipped++;
                log.warn("[评测集生成] 第 {}/{} 条失败: {}", i + 1, sampled.size(), e.getMessage());
            }
            log.info("[评测集生成] 进度 {}/{}，已生成 {} 条", i + 1, sampled.size(), items.size());
        }

        Path output = Path.of(properties.getEval().getOutputPath());
        writeJson(output, items);
        log.info("[评测集生成] 完成：生成 {} 条，跳过 {} 条，输出到 {}",
                items.size(), skipped, output.toAbsolutePath());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generated", items.size());
        summary.put("skipped", skipped);
        summary.put("output", output.toAbsolutePath().toString());
        summary.put("note", "生成结果需要人工审核后再合并进正式评测集");
        return summary;
    }

    /** 用和入库完全一样的切分逻辑，保证生成的问题对应的片段，就是检索时能召回的那个片段。 */
    private List<Chunk> splitAll(Path docsDir) {
        DocumentSplitter splitter = DocumentSplitters.recursive(MAX_SEGMENT_CHARS, SEGMENT_OVERLAP_CHARS);
        List<Chunk> chunks = new ArrayList<>();
        try (Stream<Path> files = Files.list(docsDir)) {
            List<Path> markdowns = files
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : markdowns) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (ContentSectioner.Section section : ContentSectioner.split(text)) {
                    for (TextSegment segment : splitter.split(Document.from(section.text()))) {
                        chunks.add(new Chunk(
                                file.getFileName().toString(),
                                section.title() == null ? "" : section.title(),
                                segment.text()));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取文档目录失败: " + docsDir, e);
        }
        return chunks;
    }

    /** 均匀抽样而不是随机抽样：结果可复现，而且能覆盖到每一份文档。 */
    private List<Chunk> sample(List<Chunk> chunks, int limit) {
        List<Chunk> candidates = chunks.stream()
                .filter(chunk -> chunk.text().length() >= MIN_CHUNK_CHARS)
                .toList();
        if (candidates.size() <= limit || limit < 2) {
            return candidates;
        }
        int step = candidates.size() / limit;
        List<Chunk> sampled = new ArrayList<>(limit);
        if (step > 1) {
            for (int i = 0; i < candidates.size() && sampled.size() < limit; i += step) {
                sampled.add(candidates.get(i));
            }
            return sampled;
        }
        // 候选不够分散时（步长为 1），按下标等距取，避免只抽到开头那一段文档
        for (int k = 0; k < limit; k++) {
            int index = (int) Math.round((double) k * (candidates.size() - 1) / (limit - 1));
            sampled.add(candidates.get(index));
        }
        return sampled;
    }

    private Map<String, Object> generateOne(Chunk chunk, int id, List<Map<String, Object>> existing) throws IOException {
        String prompt = PROMPT_TEMPLATE.formatted(chunk.text());
        ChatResponse response = chatModel.chat(List.<ChatMessage>of(UserMessage.from(prompt)));
        String json = extractJson(response.aiMessage().text());
        if (json == null) {
            log.warn("[评测集生成] 模型没有返回 JSON，已跳过");
            return null;
        }

        JsonNode node = objectMapper.readTree(json);
        String question = node.path("question").asText("").strip();
        List<String> rawKeywords = new ArrayList<>();
        node.path("keywords").forEach(item -> rawKeywords.add(item.asText("").strip()));

        // 硬校验一：关键词必须在原文里找得到，否则说明模型在编
        // 硬校验二：关键词不能太长，否则会跨片段被切断、永远匹配不上
        List<String> keywords = rawKeywords.stream()
                .filter(keyword -> !keyword.isEmpty()
                        && keyword.length() <= MAX_KEYWORD_CHARS
                        && chunk.text().contains(keyword))
                .distinct()
                .toList();

        if (question.isEmpty()) {
            log.warn("[评测集生成] 模型没有给出问题，已丢弃");
            return null;
        }
        if (keywords.size() < MIN_KEYWORD_COUNT) {
            log.info("[评测集生成] 有效关键词不足 {} 个（{}），已丢弃: {}",
                    MIN_KEYWORD_COUNT, keywords, question);
            return null;
        }

        // 硬校验三：问题去重。均匀抽样会取到相邻片段，很容易生成语义重复的题目
        if (isDuplicate(question, existing)) {
            log.info("[评测集生成] 与已有题目重复，已跳过: {}", question);
            return null;
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("source", chunk.source());
        item.put("section", chunk.section());
        item.put("question", question);
        item.put("keywords", keywords);
        item.put("category", "自动生成");
        return item;
    }

    private boolean isDuplicate(String question, List<Map<String, Object>> existing) {
        Set<String> candidate = bigrams(question);
        for (Map<String, Object> item : existing) {
            Set<String> other = bigrams(String.valueOf(item.get("question")));
            if (containment(candidate, other) >= DUPLICATE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /** 用字符二元组做近似比较，对中文足够有效，不需要引入额外依赖。 */
    private Set<String> bigrams(String text) {
        String normalized = text.replaceAll("[\\s，。？、：；！,.:;?!]", "");
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 1 < normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /** 交集除以较短集合：衡量"较短的那条是否基本被较长的那条覆盖"。 */
    private double containment(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        return (double) intersection.size() / Math.min(a.size(), b.size());
    }

    private String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : null;
    }

    private void writeJson(Path output, List<Map<String, Object>> items) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "自动生成评测集（待人工审核）");
        root.put("generatedAt", Instant.now().toString());
        root.put("source", properties.getIngest().getSourceDir());
        root.put("count", items.size());
        root.put("items", items);
        try {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
        } catch (IOException e) {
            throw new IllegalStateException("写评测集失败: " + output, e);
        }
    }

    private record Chunk(String source, String section, String text) {
    }
}
