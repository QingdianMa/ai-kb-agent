package com.kbagent.retrieval;

import com.kbagent.document.ChunkRecord;
import com.kbagent.document.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 关键词检索通道：自己实现的 BM25，用字符二元组做中文分词。
 * <p>
 * 为什么要这一路：向量检索按语义找，对"编号、专有名词、错误码"这类内容不敏感——
 * 它会把 {@code kb_embedding} 和 {@code kb_chunk} 编码成几乎一样的东西。
 * 而关键词检索是字面匹配，对这类内容一找一个准。两路互补，所以叫混合检索。
 * <p>
 * 为什么不用 Lucene：文档规模只有几百个片段，引入 Lucene 或 ES 的运维成本不划算。
 * 换成十万级文档时应改用 Lucene 或专门的检索引擎。
 * <p>
 * 中文分词用的是字符二元组（CJK 检索的经典做法），不需要词典，对中文效果足够好：
 * "任务分配" → "任务"、"务分"、"分配"。
 */
@Component
public class KeywordIndex {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndex.class);
    /** BM25 词频饱和参数，取经典默认值 */
    private static final double K1 = 1.2;
    /** BM25 长度归一化参数，取经典默认值 */
    private static final double B = 0.75;

    private final DocumentRepository repository;

    /** 索引快照。整体替换而不是原地修改，读的时候不用加锁。 */
    private volatile Snapshot snapshot;
    private volatile boolean dirty = true;

    public KeywordIndex(DocumentRepository repository) {
        this.repository = repository;
    }

    /** 文档有增删时调用，下次检索时重建。 */
    public void markDirty() {
        this.dirty = true;
    }

    public List<RetrievedChunk> search(String query, int topK) {
        Snapshot current = ensureFresh();
        if (current.docCount == 0) {
            return List.of();
        }

        Map<String, Double> scores = new HashMap<>();
        for (String term : new HashSet<>(tokenize(query))) {
            List<Posting> postings = current.postings.get(term);
            if (postings == null) {
                continue;
            }
            // BM25 的逆文档频率：词越稀有，权重越高
            double idf = Math.log(1 + (current.docCount - postings.size() + 0.5) / (postings.size() + 0.5));
            for (Posting posting : postings) {
                int docLength = current.lengths.getOrDefault(posting.chunkId, 1);
                // 词频饱和 + 文档长度归一化：长文档不会因为词多就占便宜
                double denominator = posting.tf + K1 * (1 - B + B * docLength / current.avgLength);
                double contribution = idf * posting.tf * (K1 + 1) / denominator;
                scores.merge(posting.chunkId, contribution, Double::sum);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> toRetrievedChunk(current, entry.getKey(), entry.getValue()))
                .toList();
    }

    private RetrievedChunk toRetrievedChunk(Snapshot current, String chunkId, double score) {
        ChunkRecord chunk = current.byId.get(chunkId);
        return new RetrievedChunk(
                chunkId,
                chunk.content(),
                current.fileNames.getOrDefault(chunkId, ""),
                chunk.section(),
                chunk.chunkIndex(),
                score);
    }

    private Snapshot ensureFresh() {
        if (dirty || snapshot == null) {
            synchronized (this) {
                if (dirty || snapshot == null) {
                    long startedAt = System.currentTimeMillis();
                    snapshot = build();
                    dirty = false;
                    log.info("[关键词索引] 重建完成：{} 个片段，{} 个词项，耗时 {} ms",
                            snapshot.docCount, snapshot.postings.size(), System.currentTimeMillis() - startedAt);
                }
            }
        }
        return snapshot;
    }

    private Snapshot build() {
        List<ChunkRecord> chunks = repository.findAllChunks();
        Map<String, List<Posting>> postings = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, ChunkRecord> byId = new HashMap<>();
        Map<String, String> fileNames = new HashMap<>();

        // 片段里只有 documentId，文件名要从文档表映射过来
        Map<java.util.UUID, String> fileNameByDocument = new HashMap<>();
        repository.findAll().forEach(document -> fileNameByDocument.put(document.id(), document.fileName()));

        for (ChunkRecord chunk : chunks) {
            List<String> terms = tokenize(chunk.content());
            lengths.put(chunk.id(), Math.max(terms.size(), 1));
            byId.put(chunk.id(), chunk);
            fileNames.put(chunk.id(), fileNameByDocument.getOrDefault(chunk.documentId(), ""));

            Map<String, Integer> termFrequency = new HashMap<>();
            terms.forEach(term -> termFrequency.merge(term, 1, Integer::sum));
            termFrequency.forEach((term, count) ->
                    postings.computeIfAbsent(term, key -> new ArrayList<>()).add(new Posting(chunk.id(), count)));
        }

        double averageLength = lengths.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(1);
        return new Snapshot(postings, lengths, byId, fileNames, Math.max(averageLength, 1), chunks.size());
    }

    /**
     * 分词：中文按字符二元组切，英文和数字按整词切。
     * 数字里的小数点特殊处理，让 {@code 17.0.13} 这种版本号保持完整。
     */
    static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        List<Character> cjk = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                flushAscii(ascii, terms);
                cjk.add(ch);
                continue;
            }
            boolean digitDot = ch == '.'
                    && i > 0 && i + 1 < text.length()
                    && Character.isDigit(text.charAt(i - 1))
                    && Character.isDigit(text.charAt(i + 1));
            if (Character.isLetterOrDigit(ch) || digitDot) {
                flushCjk(cjk, terms);
                ascii.append(Character.toLowerCase(ch));
            } else {
                flushAscii(ascii, terms);
                flushCjk(cjk, terms);
            }
        }
        flushAscii(ascii, terms);
        flushCjk(cjk, terms);
        return terms;
    }

    private static void flushAscii(StringBuilder buffer, List<String> terms) {
        if (buffer.length() >= 2) {
            terms.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private static void flushCjk(List<Character> run, List<String> terms) {
        for (int i = 0; i + 1 < run.size(); i++) {
            terms.add("" + run.get(i) + run.get(i + 1));
        }
        if (run.size() == 1) {
            terms.add(String.valueOf(run.get(0)));
        }
        run.clear();
    }

    private static boolean isCjk(char ch) {
        return ch >= 0x4E00 && ch <= 0x9FFF;
    }

    private record Posting(String chunkId, int tf) {
    }

    private record Snapshot(
            Map<String, List<Posting>> postings,
            Map<String, Integer> lengths,
            Map<String, ChunkRecord> byId,
            Map<String, String> fileNames,
            double avgLength,
            int docCount) {
    }
}
