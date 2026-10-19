package com.kbagent.web;

import com.kbagent.agent.AgentContext;
import com.kbagent.agent.KbAgentService;
import com.kbagent.web.dto.ChatAnswer;
import com.kbagent.web.dto.ChatRequest;
import com.kbagent.web.dto.SourceRef;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final KbAgentService agentService;

    public ChatController(KbAgentService agentService) {
        this.agentService = agentService;
    }

    /** 同步问答，方便写测试和做评测。 */
    @PostMapping
    public ChatAnswer chat(@RequestBody @Valid ChatRequest request) {
        long startedAt = System.currentTimeMillis();
        AgentContext context = agentService.prepare(request.question(), request.topK());
        long retrievalDoneAt = System.currentTimeMillis();
        ChatResponse response = agentService.answer(context);
        long finishedAt = System.currentTimeMillis();
        log.info("[问答] 完成: 检索 {} ms，生成 {} ms，总计 {} ms，引用 {} 段",
                retrievalDoneAt - startedAt,
                finishedAt - retrievalDoneAt,
                finishedAt - startedAt,
                context.chunks().size());
        return new ChatAnswer(response.aiMessage().text(), toSourceRefs(context));
    }

    /** 流式问答（SSE）：先推引用来源，再推 token，最后推完整答案。 */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("question") String question,
                             @RequestParam(name = "topK", required = false) Integer topK) {
        SseEmitter emitter = new SseEmitter(0L);
        long startedAt = System.currentTimeMillis();
        try {
            AgentContext context = agentService.prepare(question, topK);
            long retrievalDoneAt = System.currentTimeMillis();
            log.info("[问答] 检索完成: {} 段，耗时 {} ms", context.chunks().size(), retrievalDoneAt - startedAt);
            emit(emitter, "sources", toSourceRefs(context));

            agentService.stream(context, new StreamingChatResponseHandler() {
                /** 首字时刻，用来算"首字延迟" */
                private Long firstTokenAt = null;

                @Override
                public void onPartialResponse(String partialResponse) {
                    if (firstTokenAt == null) {
                        firstTokenAt = System.currentTimeMillis();
                        log.info("[问答] 首字延迟: {} ms", firstTokenAt - startedAt);
                    }
                    emit(emitter, "delta", partialResponse);
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    long finishedAt = System.currentTimeMillis();
                    long firstTokenMs = firstTokenAt == null ? -1 : firstTokenAt - startedAt;
                    log.info("[问答] 完成: 检索 {} ms，首字 {} ms，总计 {} ms，引用 {} 段",
                            retrievalDoneAt - startedAt, firstTokenMs, finishedAt - startedAt,
                            context.chunks().size());
                    // 把服务端测到的耗时一起推给前端，方便对照
                    emit(emitter, "done", Map.of(
                            "answer", completeResponse.aiMessage().text(),
                            "retrievalMs", retrievalDoneAt - startedAt,
                            "firstTokenMs", firstTokenMs,
                            "totalMs", finishedAt - startedAt));
                    emitter.complete();
                }

                @Override
                public void onError(Throwable error) {
                    log.warn("流式生成失败", error);
                    emit(emitter, "error", Map.of("message", String.valueOf(error.getMessage())));
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            log.warn("问答请求失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private List<SourceRef> toSourceRefs(AgentContext context) {
        var chunks = context.chunks();
        return java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(i -> SourceRef.of(i + 1, chunks.get(i)))
                .toList();
    }

    private void emit(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception e) {
            // 客户端断开是常态，不需要当成错误往上抛
            log.debug("SSE 发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
