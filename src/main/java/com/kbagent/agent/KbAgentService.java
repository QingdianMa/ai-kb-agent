package com.kbagent.agent;

import com.kbagent.retrieval.RetrievalService;
import com.kbagent.retrieval.RetrievedChunk;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 的"生成"阶段：把召回片段拼成带编号的参考资料，再让模型基于资料回答。
 * 关键约束只有两条：不许编造、必须标引用。
 */
@Service
public class KbAgentService {

    private static final int MAX_CHUNK_CHARS_IN_PROMPT = 1200;

    private static final String SYSTEM_PROMPT = """
            你是企业知识库问答助手，只依据用户消息中的【参考资料】回答问题。

            必须遵守的规则：
            1. 只使用参考资料中的信息，不得引入外部知识，不得凭常识推测。
            2. 如果资料不足以回答，直接说明"根据现有资料无法确定"，并指出还缺什么信息。
            3. 每个结论后面用 [1]、[2] 这样的编号标注来源，编号必须与参考资料序号一致。
            4. 多条资料互相冲突时，指出冲突并分别标注来源，不要擅自选一个。
            5. 用简洁的中文回答，先给结论，再给依据，不要重复资料原文。
            6. 排版只用 Markdown 的加粗、短横线列表和有序列表；不要输出表格、代码块、HTML 标签或链接语法。
            """;

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final RetrievalService retrievalService;

    public KbAgentService(ChatModel chatModel,
                          StreamingChatModel streamingChatModel,
                          RetrievalService retrievalService) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.retrievalService = retrievalService;
    }

    public AgentContext prepare(String question, Integer topK) {
        List<RetrievedChunk> chunks = retrievalService.retrieve(question, topK);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT));
        messages.add(UserMessage.from(buildUserPrompt(question, chunks)));
        return new AgentContext(List.copyOf(messages), chunks, question);
    }

    public ChatResponse answer(AgentContext context) {
        return chatModel.chat(context.messages());
    }

    public void stream(AgentContext context, StreamingChatResponseHandler handler) {
        streamingChatModel.chat(context.messages(), handler);
    }

    private String buildUserPrompt(String question, List<RetrievedChunk> chunks) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("【参考资料】\n");
        if (chunks.isEmpty()) {
            prompt.append("（没有检索到任何相关资料）\n");
        } else {
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk chunk = chunks.get(i);
                prompt.append('[').append(i + 1).append("] 文件：")
                        .append(nullSafe(chunk.fileName()));
                if (chunk.section() != null && !chunk.section().isBlank()) {
                    prompt.append("｜章节：").append(chunk.section());
                }
                prompt.append('\n')
                        .append(truncate(chunk.text()))
                        .append("\n\n");
            }
        }
        prompt.append("【用户问题】\n").append(question);
        return prompt.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_CHUNK_CHARS_IN_PROMPT
                ? text
                : text.substring(0, MAX_CHUNK_CHARS_IN_PROMPT) + "...";
    }

    private String nullSafe(String value) {
        return value == null ? "未知文件" : value;
    }
}
