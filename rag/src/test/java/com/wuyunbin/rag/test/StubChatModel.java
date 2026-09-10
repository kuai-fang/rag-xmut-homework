package com.wuyunbin.rag.test;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 桩 ChatModel：返回固定字符串，并记录每次收到的 Prompt 便于断言多轮记忆与 RAG 上下文。
 */
public class StubChatModel implements ChatModel {

    private final String fixedReply;
    private final List<Prompt> requestedPrompts = new CopyOnWriteArrayList<>();

    public StubChatModel(String fixedReply) {
        this.fixedReply = fixedReply;
    }

    public StubChatModel() {
        this("这是桩模型回复");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        requestedPrompts.add(prompt);
        var assistant = new AssistantMessage(fixedReply);
        return new ChatResponse(List.of(new Generation(assistant)));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    /** 清空捕获记录（测试间隔离）。 */
    public void reset() {
        requestedPrompts.clear();
    }

    /** 最近一次收到的 Prompt。 */
    public Prompt lastPrompt() {
        return requestedPrompts.isEmpty() ? null : requestedPrompts.get(requestedPrompts.size() - 1);
    }

    public List<Prompt> requestedPrompts() {
        return requestedPrompts;
    }
}