package com.kbagent.agent;

import com.kbagent.retrieval.RetrievedChunk;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 一次问答所需的全部输入：拼好的消息 + 命中的片段。
 * 拆出来是为了让"检索"和"生成"两个阶段可以分别测试、分别计时。
 */
public record AgentContext(List<ChatMessage> messages, List<RetrievedChunk> chunks, String question) {
}
