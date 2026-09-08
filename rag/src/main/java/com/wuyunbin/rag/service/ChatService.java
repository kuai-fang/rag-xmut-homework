package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 单轮聊天服务。
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;

    /**
     * 单轮对话：将用户消息直接发送给模型并返回回复。
     *
     * @param message 用户消息
     * @return 模型回复
     */
    public ChatResponse chat(String message) {
        String reply = chatClient.prompt()
                .user(message)
                .call()
                .content();
        return new ChatResponse(reply);
    }
}
