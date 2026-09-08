package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单轮聊天响应体。
 */
@Schema(description = "单轮聊天响应")
public record ChatResponse(

        @Schema(description = "模型回复内容")
        String reply
) {
}
