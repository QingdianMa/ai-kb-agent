package com.kbagent.retrieval;

import com.kbagent.config.AppProperties;
import com.kbagent.infra.RedisOps;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 问题向量缓存。
 *
 * 痛点：每次问答都要把问题送进嵌入模型算一次向量，约 200 毫秒，而且是要花钱的接口调用。
 * 热点问题的重复率很高——"研究方向是什么""怎么联系"这类问题会被反复问，
 * 每次重算纯属浪费。
 *
 * 为什么只缓存「问题」不缓存「文档片段」：
 * 文档片段在一个知识库里几乎不会重复，缓存它们只会白占内存、还降低命中率。
 * 判断该不该缓存的标准是「重复读取的概率」，不是「这个数据重要不重要」。
 */
@Component
public class QueryEmbeddingCache {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbeddingCache.class);

    private final EmbeddingModel embeddingModel;
    private final RedisOps redisOps;
    private final AppProperties properties;

    /** 命中率是缓存价值的直接证据，也是面试时可以报出来的数字 */
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public QueryEmbeddingCache(EmbeddingModel embeddingModel,
                               RedisOps redisOps,
                               AppProperties properties) {
        this.embeddingModel = embeddingModel;
        this.redisOps = redisOps;
        this.properties = properties;
    }

    public Embedding embed(String query) {
        if (!Boolean.TRUE.equals(properties.getCache().getEnabled())) {
            return embeddingModel.embed(query).content();
        }

        String key = cacheKey(query);
        Optional<String> cached = redisOps.get(key);
        if (cached.isPresent()) {
            hits.incrementAndGet();
            return Embedding.from(decode(cached.get()));
        }

        Embedding embedding = embeddingModel.embed(query).content();
        misses.incrementAndGet();
        redisOps.set(key, encode(embedding.vector()),
                Duration.ofHours(properties.getCache().getTtlHours()));
        return embedding;
    }

    public Map<String, Object> stats() {
        long hitCount = hits.get();
        long missCount = misses.get();
        long total = hitCount + missCount;
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("hits", hitCount);
        stats.put("misses", missCount);
        stats.put("hitRate", total == 0 ? 0 : Math.round(hitCount * 10000.0 / total) / 10000.0);
        return stats;
    }

    /**
     * key 用问题文本的哈希，不用原文：一是避免超长 key，二是避免把访客的问题明文写进 Redis。
     * 加上模型名，是为了将来换嵌入模型时缓存自动失效，不会读到旧模型算的向量。
     */
    private String cacheKey(String query) {
        return "kb:emb:" + properties.getEmbedding().getModel() + ":" + sha256(query);
    }

    private String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少 SHA-256 实现", e);
        }
    }

    /**
     * 用 base64 编码 float32 数组，而不是存成 JSON。
     * 1024 维向量：JSON 大约 20 KB，base64 只有约 5.5 KB，省了四分之三的 Redis 内存。
     */
    private String encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private float[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
