package com.kbagent.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型的装配集中在这里。留一个心眼看清楚：
 * 对话模型、向量模型、向量库是三个完全独立的部件，可以任意替换供应商。
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class LangChainConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChainConfig.class);
    private static final String MISSING_KEY_PLACEHOLDER = "missing-api-key";

    @Bean
    public ChatModel chatModel(AppProperties props) {
        AppProperties.Chat chat = props.getChat();
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(requireKey(chat.getApiKey(), "KB_CHAT_API_KEY（DeepSeek）"))
                .modelName(chat.getModel())
                .temperature(chat.getTemperature())
                .timeout(Duration.ofSeconds(chat.getTimeoutSeconds()))
                .logRequests(chat.isLogRequests())
                .logResponses(chat.isLogResponses());
        applyThinkingMode(builder, chat.getThinking());
        return builder.build();
    }

    @Bean
    public StreamingChatModel streamingChatModel(AppProperties props) {
        AppProperties.Chat chat = props.getChat();
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .baseUrl(chat.getBaseUrl())
                .apiKey(requireKey(chat.getApiKey(), "KB_CHAT_API_KEY（DeepSeek）"))
                .modelName(chat.getModel())
                .temperature(chat.getTemperature())
                .timeout(Duration.ofSeconds(chat.getTimeoutSeconds()));
        applyThinkingMode(builder, chat.getThinking());
        return builder.build();
    }

    /**
     * DeepSeek 走 OpenAI 兼容协议，但思考模式开关是它自己的扩展字段，
     * 官方要求在请求体顶层传 {"thinking":{"type":"disabled"}}。
     * LangChain4j 的 customParameters 正好会被序列化到请求体顶层，用它最省事。
     */
    private void applyThinkingMode(Object builder, String thinking) {
        if (thinking == null || thinking.isBlank()) {
            return;
        }
        Map<String, Object> custom = new LinkedHashMap<>();
        custom.put("thinking", Map.of("type", thinking.trim().toLowerCase()));

        if (builder instanceof OpenAiChatModel.OpenAiChatModelBuilder chatBuilder) {
            chatBuilder.customParameters(custom);
        } else if (builder instanceof OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder streamBuilder) {
            streamBuilder.customParameters(custom);
        }
    }

    @Bean
    public EmbeddingModel embeddingModel(AppProperties props) {
        AppProperties.Embedding embedding = props.getEmbedding();
        var builder = OpenAiEmbeddingModel.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(requireKey(embedding.getApiKey(), "KB_EMBED_API_KEY（向量模型）"))
                .modelName(embedding.getModel())
                .timeout(Duration.ofSeconds(embedding.getTimeoutSeconds()));

        // dimensions 是 OpenAI 专有参数：text-embedding-3 系列用它裁剪输出维度，
        // 但硅基流动、百炼这类兼容服务不认这个字段，带上会直接返回 20015 参数错误。
        // 向量库的实际维度仍然由 kb.embedding.dimension 决定，两者互不影响。
        if (embedding.isSendDimensionParameter()) {
            builder.dimensions(embedding.getDimension());
        }
        return builder.build();
    }

    /** 生产路径：pgvector。 */
    @Bean
    @Profile("!dev")
    public EmbeddingStore<TextSegment> pgVectorEmbeddingStore(AppProperties props) {
        AppProperties.Storage storage = props.getStorage();
        return PgVectorEmbeddingStore.builder()
                .host(storage.getHost())
                .port(storage.getPort())
                .database(storage.getDatabase())
                .user(storage.getUser())
                .password(storage.getPassword())
                .table(storage.getVectorTable())
                .dimension(props.getEmbedding().getDimension())
                .createTable(true)
                .build();
    }

    /** 本地开发路径：进程内向量库，不需要数据库，重启即清空。 */
    @Bean
    @Profile("dev")
    public EmbeddingStore<TextSegment> inMemoryEmbeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    /**
     * 没配 key 时不让应用启动失败，改成启动后调用报错。
     * 原因：本地先把页面跑起来看效果是刚需，key 有可能会晚一步才拿到。
     */
    private String requireKey(String apiKey, String description) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("配置项 {} 还没有设置，模型调用会返回鉴权错误。请参考项目根目录的 .env.example 填好后重启。",
                    description);
            return MISSING_KEY_PLACEHOLDER;
        }
        return apiKey;
    }
}
