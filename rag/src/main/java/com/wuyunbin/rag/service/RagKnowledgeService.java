package com.wuyunbin.rag.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wuyunbin.rag.dto.RagImportResult;
import com.wuyunbin.rag.dto.SearchHit;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 知识库服务：文档导入向量化、基于向量的语义检索。
 * 文档 -> 分块 -> 嵌入向量 -> 写入 Milvus
 */
@Service
public class RagKnowledgeService {

    private final VectorStore vectorStore;

    @Value("${spring.ai.vectorstore.milvus.collection-name}")
    private String collectionName;

    /** 文档可选兜底目录：相对路径解析找不到时，按文件名在该目录内查找。 */
    @Value("${rag.import.docs-dir:rag/docs}")
    private String docsDir;

    // 中文分块参数：每块约 500 字符，块间重叠 120 字符，避免语义被截断
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 120;

    public RagKnowledgeService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * 导入一份文本文件到 Milvus 知识库。
     *
     * @param filePath 文件路径。支持：绝对路径；相对应用工作目录的路径（含 ../）；
     *                 若按上述解析不到，则回退到文档目录（rag.import.docs-dir）按文件名查找。
     * @return 导入块数等结果
     */
    public RagImportResult importDocument(String filePath) throws IOException {
        Path path = resolveImportPath(filePath);
        String text = Files.readString(path);
        List<String> chunks = chunk(text);

        List<Document> documents = new ArrayList<>(chunks.size());
        int index = 0;
        for (String chunk : chunks) {
            if (chunk.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", path.getFileName().toString());
            metadata.put("chunk_index", index++);
            documents.add(new Document(chunk, metadata));
        }

        // 逐块嵌入并写入 Milvus
        vectorStore.add(documents);

        return new RagImportResult(documents.size(), collectionName, documents.size());
    }

    /**
     * 解析导入路径，返回第一个实际存在的文件路径（规范化为绝对路径）。
     * 候选顺序：①直接解析（绝对或相对工作目录，含 ../）②文档目录下按文件名。
     */
    Path resolveImportPath(String filePath) throws IOException {
        String base = filePath == null ? "" : filePath.trim();
        if (base.isEmpty()) {
            throw new FileNotFoundException("filePath 不能为空");
        }

        Path direct = Path.of(base).toAbsolutePath().normalize();
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        Path byName = docsDirPath().resolve(Path.of(base).getFileName().toString()).toAbsolutePath().normalize();
        if (Files.isRegularFile(byName)) {
            return byName;
        }

        throw new FileNotFoundException("文件不存在或不可读，已尝试：\n  1) " + direct + "\n  2) " + byName);
    }

    /** 文档兜底目录在本机上的绝对路径（不存在则自动创建）。 */
    private Path docsDirPath() {
        Path dir = Path.of(docsDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // 目录创建失败不阻塞；最终以文件是否可读为准
        }
        return dir;
    }

    /**
     * 相似度检索：在 Milvus 中查询与问题最相关的前 topK 条知识。
     */
    public List<SearchHit> search(String query, int topK) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(doc -> new SearchHit(doc.getText(), doc.getMetadata(), doc.getScore()))
                .toList();
    }

    @SuppressWarnings("unused")
    private List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        int length = normalized.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + CHUNK_SIZE, length);
            chunks.add(normalized.substring(start, end));
            if (end == length) {
                break;
            }
            // 向后移动时保留重叠部分，避免切词割裂语义
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return chunks;
    }
}