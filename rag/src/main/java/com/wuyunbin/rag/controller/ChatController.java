package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.ChatRequest;
import com.wuyunbin.rag.dto.ChatResponse;
import com.wuyunbin.rag.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多轮问答接口。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Tag(name = "聊天接口", description = "多轮知识增强问答相关接口")
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    @Operation(summary = "多轮问答", description = "向模型发送消息，结合知识库检索与多轮会话记忆返回回复")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary = "清空会话", description = "清空指定会话的历史记忆（幂等）")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        chatService.clearSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}