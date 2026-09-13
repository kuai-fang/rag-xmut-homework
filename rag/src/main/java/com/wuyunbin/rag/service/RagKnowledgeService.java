package com.wuyunbin.rag.service;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wuyunbin.rag.dto.RagImportResult;
import com.wuyunbin.rag.dto.SearchHit;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 知识库服务：文档导入向量化、基于向量的语义检索。
 * 文档 -> 清洗（曲调过滤 + 表格识别）-> 标题状态机切块 -> 超长兜底 -> 嵌入向量 -> 写入 Milvus
 */
@Service
public class RagKnowledgeService {

    private final VectorStore vectorStore;

    @Value("${spring.ai.vectorstore.milvus.collection-name}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost = "localhost";

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort = 19530;

    /** 文档可选兜底目录：相对路径解析找不到时，按文件名在该目录内查找。 */
    @Value("${rag.import.docs-dir:rag/docs}")
    private String docsDir = "rag/docs";

    /** 语义块最大字符数，超长块走段落兜底切分 */
    @Value("${rag.chunk.max-chars:500}")
    private int maxChunkChars = 500;

    /** 超长兜底时子块间重叠字符数 */
    @Value("${rag.chunk.overlap-chars:120}")
    private int chunkOverlapChars = 120;

    /** 是否启用曲调行过滤（校歌简谱） */
    @Value("${rag.chunk.enable-melody-filter:true}")
    private boolean enableMelodyFilter = true;

    /** 是否启用表格保整识别 */
    @Value("${rag.chunk.enable-table-preserve:true}")
    private boolean enableTablePreserve = true;

    /** 是否启用标题层级状态机切块 */
    @Value("${rag.chunk.enable-heading-chunk:true}")
    private boolean enableHeadingChunk = true;

    // 预编译正则
    private static final Pattern HEADING_PATTERN = Pattern.compile("^\\s*(#{1,6})\\s+");
    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

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

        // 第一步：clean —— 曲调行过滤 + 表格识别保整
        List<Chunk> cleanedChunks = clean(text);

        // 第二步：chunk —— 标题状态机切块 + 超长兜底
        List<String> finalChunks = new ArrayList<>();
        for (Chunk cleaned : cleanedChunks) {
            if (cleaned.isTable) {
                // 表格块整体加入
                if (!cleaned.content.isBlank()) {
                    finalChunks.add(cleaned.content);
                }
            } else {
                // 正文按标题切块
                List<String> headingChunks = headingChunk(cleaned.content);
                finalChunks.addAll(headingChunks);
            }
        }

        // 构建带元数据的 Document 对象列表
        List<Document> documents = new ArrayList<>(finalChunks.size());
        int index = 0;
        for (String chunk : finalChunks) {
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
     * 清空整个知识库集合（drop collection），用于全量重建前的重置。
     */
    public String clearCollection() {
        ConnectConfig config = ConnectConfig.builder().uri("http://" + milvusHost + ":" + milvusPort).build();
        MilvusClientV2 client = new MilvusClientV2(config);
        try {
            client.dropCollection(DropCollectionReq.builder().collectionName(collectionName).build());
            return "已删除集合 " + collectionName;
        } finally {
            client.close();
        }
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
     * 若 similarityThreshold 非空，则仅保留相似度不低于该值的命中。
     */
    public List<SearchHit> search(String query, int topK, Double similarityThreshold) {
        SearchRequest.Builder builder = SearchRequest.builder().query(query).topK(topK);
        if (similarityThreshold != null) {
            builder.similarityThreshold(similarityThreshold);
        }
        return vectorStore.similaritySearch(builder.build()).stream()
                .map(doc -> new SearchHit(doc.getText(), doc.getMetadata(), doc.getScore()))
                .toList();
    }

    /**
     * clean 阶段：行级预处理。
     * 1. 曲调行过滤：整行都是数字/点/横线/竖线/空格 → 丢弃
     * 2. 表格识别保整：识别Markdown管道表格，整体作为一个原子块输出
     * 输出：List<Chunk> —— 要么是纯文本块，要么是表格块
     */
    private List<Chunk> clean(String text) {
        List<Chunk> result = new ArrayList<>();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        String currentText = "";
        List<String> currentTable = null;
        boolean inTable = false;
        boolean nextLineMayBeTableHeader = false;

        for (String line : lines) {
            // 如果已经在表格状态，检查是否退出
            if (inTable) {
                if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                    // 仍是表格行，累积
                    currentTable.add(line);
                    continue;
                } else if (isHeading(line) || line.isBlank()) {
                    // 标题/空行 → 结束表格，输出整块
                    inTable = false;
                    result.add(new Chunk(String.join("\n", currentTable), true));
                    currentTable = null;
                    // 当前行作为普通文本处理（下文会继续走）
                } else {
                    // 非 | 开头但非退出信号 → 仍当表格行（可能无管道首尾）
                    currentTable.add(line);
                    continue;
                }
            }

            // ==== NORMAL 状态处理当前行 ====
            // 表格分隔行检测（须在曲调过滤之前，避免 `| --- |` 被误判为简谱行）
            if (enableTablePreserve && isTableSeparator(line)) {
                // 上一行可能是表头，将表头加入表格
                currentTable = new ArrayList<>();
                if (nextLineMayBeTableHeader && !currentText.isBlank()) {
                    // 把最后一段 text 切出最后一行作为表头
                    String[] prevLines = currentText.split("\n");
                    currentTable.add(prevLines[prevLines.length - 1]);
                    currentTable.add(line);
                    // 移除表头后剩余文本
                    currentText = String.join("\n", java.util.Arrays.copyOfRange(prevLines, 0, prevLines.length - 1));
                } else {
                    currentTable.add(line);
                }
                inTable = true;
                continue;
            }

            // 曲调行过滤
            if (enableMelodyFilter && isMelodyLine(line)) {
                continue;
            }

            // 检测到下一行可能是表格分隔 → 缓存当前行可能是表头
            if (enableTablePreserve && line.trim().startsWith("|") && line.trim().endsWith("|")) {
                nextLineMayBeTableHeader = true;
            } else {
                nextLineMayBeTableHeader = false;
            }

            // 普通文本累积
            if (!currentText.isEmpty()) {
                currentText += "\n";
            }
            currentText += line;
        }

        // 文档结束，flush 剩余内容
        if (inTable && !currentTable.isEmpty()) {
            result.add(new Chunk(String.join("\n", currentTable), true));
        } else if (!currentText.isBlank()) {
            result.add(new Chunk(currentText, false));
        }

        return result;
    }

    /**
     * 判断一行是否为曲调简谱行（图2）：全部字符都是数字、点、横线、竖线、空格 → 过滤掉。
     */
    private boolean isMelodyLine(String line) {
        if (line.isBlank()) {
            return false; // 空行由后续 blank 过滤处理
        }
        for (char c : line.toCharArray()) {
            if (!Character.isDigit(c) && c != '.' && c != '-' && c != '|' && !Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断一行是否为 Markdown 表格分隔行（图3）。
     */
    private boolean isTableSeparator(String line) {
        Matcher matcher = TABLE_SEPARATOR_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return false;
        }
        // 必须至少有一个 '-' 或 ':' 分隔符
        return line.contains("-") || line.contains(":");
    }

    /**
     * 判断一行是否为 Markdown 标题。
     */
    private boolean isHeading(String line) {
        return HEADING_PATTERN.matcher(line.trim()).find();
    }

    /**
     * 标题层级状态机切块（图1）。
     * 规则：
     * - 一级标题 # → 触发新块
     * - 二级标题 ## → 触发新块
     * - 三级及以上 ###+ → 不触发新块，继续累积在当前块
     * - 文档结束 → flush
     */
    private List<String> headingChunk(String text) {
        List<String> result = new ArrayList<>();

        // 如果标题切块未开启，则退回固定长度切分
        if (!enableHeadingChunk) {
            return fixedLengthChunk(text);
        }

        String[] lines = text.split("\n", -1);
        List<String> buffer = new ArrayList<>();

        for (String line : lines) {
            Matcher matcher = HEADING_PATTERN.matcher(line.trim());
            if (!matcher.find()) {
                // 非标题行，直接累积
                buffer.add(line);
                continue;
            }

            // 计算标题层级
            int level = matcher.group(1).length();
            if (level > 2) {
                // 三级及以上小标题，不触发新块，直接累积
                buffer.add(line);
                continue;
            }

            // 一级/二级标题触发 flush
            if (!buffer.isEmpty()) {
                String combined = String.join("\n", buffer);
                splitAndAdd(combined, result);
                buffer.clear();
            }

            // 开始新块，写入当前标题行
            buffer.add(line);
        }

        // 文档结束，flush 最后一块
        if (!buffer.isEmpty()) {
            String combined = String.join("\n", buffer);
            splitAndAdd(combined, result);
        }

        return result;
    }

    /**
     * 如果一个块超长，按段落/句子兜底切分，每个子块保留标题前缀。
     */
    private void splitAndAdd(String block, List<String> result) {
        int len = block.length();
        if (len <= maxChunkChars) {
            result.add(block);
            return;
        }

        // 提取标题行前缀（所有 # 开头行拼接）
        List<String> lines = block.lines().toList();
        StringBuilder titlePrefix = new StringBuilder();
        int contentStart = 0;
        for (String line : lines) {
            if (isHeading(line)) {
                titlePrefix.append(line).append("\n");
                contentStart++;
            } else {
                break;
            }
        }
        String prefix = titlePrefix.toString();
        int prefixLen = prefix.length();

        // 将内容拼接成字符串，方便后续切分
        String content = String.join("\n", lines.subList(contentStart, lines.size()));
        int contentLen = content.length();
        if (contentLen == 0) {
            return; // 全为标题行，无需切分
        }

        // 开始切分，每个子块带前缀
        int start = 0;
        while (start < contentLen) {
            // 计算当前子块结束位置：不超过 max - prefixLen
            int available = maxChunkChars - prefixLen;
            if (available < 10) {
                // 标题前缀本身过长（极端），退化为主内容硬切
                available = maxChunkChars;
            }
            int end = Math.min(start + available, contentLen);

            // 尽量在段落分隔 (\n\n) 处切分
            int candidate = content.lastIndexOf("\n\n", end);
            if (candidate > start + (available / 2)) {
                end = candidate + 2; // 保留段落分隔
            } else {
                // 找不到合适段落分隔，尝试在句号处切分
                candidate = content.lastIndexOf("。", end);
                if (candidate > start + (available / 2)) {
                    end = candidate + 1;
                }
            }

            String subChunk = prefix + content.substring(start, end);
            result.add(subChunk);

            if (end == contentLen) {
                break;
            }

            // 后退重叠
            start = Math.max(start + 1, end - chunkOverlapChars);
        }
    }

    /**
     * 回退方案：固定长度切分（原实现保留）。
     */
    private List<String> fixedLengthChunk(String text) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            int end = Math.min(start + maxChunkChars, length);
            chunks.add(text.substring(start, end));
            if (end == length) {
                break;
            }
            start = Math.max(start + 1, end - chunkOverlapChars);
        }
        return chunks;
    }

    /**
     * 内部块结构：区分文本块和表格块。
     */
    private static class Chunk {
        final String content;
        final boolean isTable;
        Chunk(String content, boolean isTable) {
            this.content = content;
            this.isTable = isTable;
        }
    }
}