package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 检索请求。
 */
@Schema(description = "检索请求")
public record RagSearchRequest(
        @Schema(description = "查询问题", example = "新生报到需要带什么证件？", requiredMode = Schema.RequiredMode.REQUIRED)
        String query,
        @Schema(description = "返回条数，默认 5", example = "5")
        Integer topK
) {
}