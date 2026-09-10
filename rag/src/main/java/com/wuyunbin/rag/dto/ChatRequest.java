package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 多轮聊天请求体。
 */
@Schema(description = "多轮聊天请求")
public record ChatRequest(

        @Schema(description = "用户输入的消息内容", example = "厦门理工学院创建于哪一年？", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "message 不能为空")
        @Size(max = 4000, message = "message 长度不能超过 4000 个字符")
        String message,

        @Schema(description = "会话 ID；可空，空/缺省由后端生成并随响应返回，客户端需回传以延续同一会话", example = "")
        String sessionId,

        @Schema(description = "是否启用知识库检索（RAG），默认 true", example = "true")
        Boolean ragEnabled,

        @Schema(description = "知识库检索条数，默认 5", example = "5")
        @Min(value = 1, message = "topK 必须在 1~20 之间")
        @Max(value = 20, message = "topK 必须在 1~20 之间")
        Integer topK
) {

    /** 是否启用 RAG（缺省视为 true）。*/
    public boolean isRagEnabledOrDefault() {
        return ragEnabled == null || ragEnabled;
    }

    /** topK 缺省值。*/
    public int resolvedTopK() {
        return topK == null || topK < 1 ? 5 : Math.min(topK, 20);
    }
}