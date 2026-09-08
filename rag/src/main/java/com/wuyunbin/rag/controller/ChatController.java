package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.ChatRequest;
import com.wuyunbin.rag.dto.ChatResponse;
import com.wuyunbin.rag.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 聊天接口。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "聊天接口", description = "单轮对话相关接口")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @Operation(summary = "单轮聊天", description = "向模型发送一条消息，返回模型回复（无上下文记忆）")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.message());
    }
}
