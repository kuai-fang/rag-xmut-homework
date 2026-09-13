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
        Integer topK,
        @Schema(description = "相似度阈值(0~1)，仅返回相似度不低于该值的片段；不传则不过滤", example = "0.65")
        Double similarityThreshold
) {
}