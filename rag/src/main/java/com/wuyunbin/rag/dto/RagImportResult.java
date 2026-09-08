package com.wuyunbin.rag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文档导入结果。
 */
@Schema(description = "文档导入结果")
public record RagImportResult(
        @Schema(description = "导入的分块数量") int chunks,
        @Schema(description = "写入的知识库集合名") String collection,
        @Schema(description = "集合中的向量总数") int total
) {
}