package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 多轮聊天响应体。
 */
@Schema(description = "多轮聊天响应")
public record ChatResponse(

        @Schema(description = "模型回复内容")
        String reply,

        @Schema(description = "会话 ID，客户端需回传以延续同一会话")
        String sessionId,

        @Schema(description = "命中的知识库片段（仅开启 RAG 且检索到结果时返回，可为 null）")
        java.util.List<SearchHit> sources
) {

    public static ChatResponse of(String reply, String sessionId, java.util.List<SearchHit> sources) {
        return new ChatResponse(reply, sessionId, sources);
    }
}