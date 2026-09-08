package com.wuyunbin.rag.dto;

import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 检索命中的一条知识。
 */
@Schema(description = "检索命中的一条知识")
public record SearchHit(
        @Schema(description = "命中的文本内容") String content,
        @Schema(description = "元数据") Map<String, Object> metadata,
        @Schema(description = "相似度分数") Double score
) {
}