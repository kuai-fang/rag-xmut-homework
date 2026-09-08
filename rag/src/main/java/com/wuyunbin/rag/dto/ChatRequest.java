package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单轮聊天请求体。
 */
@Schema(description = "单轮聊天请求")
public record ChatRequest(

        @Schema(description = "用户输入的消息内容", example = "你好，请介绍一下你自己。", requiredMode = Schema.RequiredMode.REQUIRED)
        String message
) {
}
