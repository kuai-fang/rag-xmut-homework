package com.wuyunbin.rag.controller;

import java.util.List;

import com.wuyunbin.rag.dto.RagImportResult;
import com.wuyunbin.rag.dto.RagSearchRequest;
import com.wuyunbin.rag.dto.SearchHit;
import com.wuyunbin.rag.service.MilvusQueryService;
import com.wuyunbin.rag.service.RagKnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库（RAG）接口：文档导入、语义检索、Milvus 查询。
 */
@RestController
@RequestMapping("/api/rag")
@Tag(name = "知识库接口", description = "文档导入向量化、语义检索、Milvus 知识库查询")
public class RagController {

    private final RagKnowledgeService ragKnowledgeService;
    private final MilvusQueryService milvusQueryService;

    @Value("${spring.ai.vectorstore.milvus.collection-name}")
    private String collectionName;

    /** RAG 检索默认返回条数（本机寻优最优组合 topK=3）。 */
    @Value("${rag.retrieve.topk:3}")
    private int defaultTopK = 3;

    /** RAG 检索默认相似度阈值（本机寻优最优组合 0.6）。 */
    @Value("${rag.retrieve.similarity-threshold:0.6}")
    private double defaultSimilarityThreshold = 0.6;

    public RagController(RagKnowledgeService ragKnowledgeService, MilvusQueryService milvusQueryService) {
        this.ragKnowledgeService = ragKnowledgeService;
        this.milvusQueryService = milvusQueryService;
    }

    @PostMapping("/import")
    @Operation(summary = "导入文档到知识库",
            description = "读取指定文本文件，分块后由 bge-m3 嵌入向量化，写入 Milvus 集合")
    public RagImportResult importDocument(@RequestParam(defaultValue = "../jmu新生手册.txt") String filePath) throws Exception {
        return ragKnowledgeService.importDocument(filePath);
    }

    @PostMapping("/clear")
    @Operation(summary = "清空知识库集合", description = "删除整个 Milvus 集合，用于全量重建前的重置")
    public String clearCollection() {
        return ragKnowledgeService.clearCollection();
    }

    @PostMapping("/search")
    @Operation(summary = "语义检索", description = "基于向量相似度检索知识库中最相关的 topK 条知识")
    public List<SearchHit> search(@RequestBody RagSearchRequest request) {
        int topK = request.topK() == null ? defaultTopK : request.topK();
        double threshold = request.similarityThreshold() != null
                ? request.similarityThreshold() : defaultSimilarityThreshold;
        return ragKnowledgeService.search(request.query(), topK, threshold);
    }

    @GetMapping("/knowledge-base")
    @Operation(summary = "查询知识库列表", description = "列出 Milvus 中的所有集合（知识库）")
    public String listKnowledgeBase() {
        return milvusQueryService.listCollections();
    }

    @GetMapping("/vectors")
    @Operation(summary = "查询前 N 条向量化数据",
            description = "从 Milvus 中取出指定知识库前 N 条向量化数据（含文本与 embedding）")
    public String listTopN(
            @RequestParam(defaultValue = "knowledge_base") String collection,
            @RequestParam(defaultValue = "10") int limit) {
        return milvusQueryService.topNVectors(collection, limit);
    }
}