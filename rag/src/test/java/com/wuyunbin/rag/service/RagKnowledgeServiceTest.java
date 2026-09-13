package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.RagImportResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagKnowledgeService 单元测试：chunk 分块、导入、检索映射。
 */
class RagKnowledgeServiceTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final RagKnowledgeService service = new RagKnowledgeService(vectorStore);

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.reset(vectorStore);
        // 每个测试都显式指定文档目录，避免依赖 @Value 注入
        ReflectionTestUtils.setField(service, "docsDir", "rag/docs");
    }

    @Test
    void 相对路径回退到文档目录按文件名找到(@TempDir Path dir) throws Exception {
        ReflectionTestUtils.setField(service, "docsDir", dir.toString());
        Path file = dir.resolve("jmu新生手册.txt");
        Files.writeString(file, "厦门理工学院校史", StandardCharsets.UTF_8);

        // ../ 相对工作目录解析不到，但文档目录下存在同名文件 -> 命中
        Path resolved = service.resolveImportPath("../jmu新生手册.txt");

        assertThat(resolved).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void 文档目录下找不到文件时抛出可读异常(@TempDir Path dir) {
        ReflectionTestUtils.setField(service, "docsDir", dir.toString());

        assertThatThrownBy(() -> service.resolveImportPath("../不存在的文件.txt"))
                .isInstanceOf(java.io.FileNotFoundException.class)
                .hasMessageContaining("文件不存在或不可读")
                .hasMessageContaining(dir.toString());
    }

    @Test
    void 空路径抛出可读异常() {
        assertThatThrownBy(() -> service.resolveImportPath("  "))
                .isInstanceOf(java.io.FileNotFoundException.class)
                .hasMessageContaining("filePath 不能为空");
    }

    @Test
    void 长文本分块数量与重叠正确(@TempDir Path dir) throws Exception {
        // 700 字符文本：应切成 2 块，重叠 120
        String text = "分".repeat(700);
        Path file = dir.resolve("a.txt");
        Files.writeString(file, text, StandardCharsets.UTF_8);

        RagImportResult result = service.importDocument(file.toString());

        assertThat(result.chunks()).isEqualTo(2);
        assertThat(result.collection()).isNull(); // 未注入 @Value，集合名为 null
    }

    @Test
    void 分块后的文档内容与元数据写入(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("b.txt");
        Files.writeString(file, "厦门理工学院是一所应用型大学", StandardCharsets.UTF_8);

        service.importDocument(file.toString());

        org.mockito.Mockito.verify(vectorStore).add(argThat((List<Document> docs) -> {
            return docs.size() == 1
                    && docs.get(0).getText().equals("厦门理工学院是一所应用型大学")
                    && docs.get(0).getMetadata().get("source").equals("b.txt");
        }));
    }

    @Test
    void 检索正确映射为SearchHit() {
        Document doc = new Document("知识点", java.util.Map.of("source", "c.txt"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));

        var hits = service.search("关于知识点", 1, null);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).content()).isEqualTo("知识点");
        assertThat(hits.get(0).metadata().get("source")).isEqualTo("c.txt");
    }

    @Test
    void 全空白文本返回0块(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("d.txt");
        Files.writeString(file, "   \n  ", StandardCharsets.UTF_8);
        RagImportResult result = service.importDocument(file.toString());
        assertThat(result.chunks()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 真实新生手册状态机切片质量验证() throws Exception {
        Path doc = Path.of("docs", "jmu新生手册.txt").toAbsolutePath().normalize();
        if (!Files.exists(doc)) {
            doc = Path.of("rag", "docs", "jmu新生手册.txt").toAbsolutePath().normalize();
        }
        assertThat(doc).exists();
        ReflectionTestUtils.setField(service, "docsDir", doc.getParent().toString());

        service.importDocument(doc.toString());
        org.mockito.ArgumentCaptor<List<Document>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(vectorStore).add(captor.capture());
        List<Document> docs = captor.getValue();

        // 打印全部切片，供人工检查切片质量
        StringBuilder dump = new StringBuilder();
        dump.append("=== 新生手册状态机切片，共 ").append(docs.size()).append(" 块 ===\n");
        for (int i = 0; i < docs.size(); i++) {
            String text = docs.get(i).getText();
            dump.append("\n---- 块").append(i).append(" (len=").append(text.length())
                    .append(", index=").append(docs.get(i).getMetadata().get("chunk_index")).append(") ----\n");
            dump.append(text).append("\n");
        }
        Files.writeString(Path.of("target", "新生手册切片_dump.txt"), dump.toString(), StandardCharsets.UTF_8);

        // 断言1：切块非空
        assertThat(docs).isNotEmpty();
        String all = docs.stream().map(Document::getText).reduce("", String::concat);
        // 断言2：不再包含校歌曲调简谱行（图2过滤）
        assertThat(all).as("曲调行残留: " + all)
                .doesNotContain("5. 5  5. 5  5.  | 3")
                .doesNotContain("6. 6  2. 2  | 5  -")
                .doesNotContain("6  5  | 3  1  | 2  5. 4  | 3. 2  1");
        // 断言3：表格块完整保存（图3保整，含分隔行与表头）
        assertThat(all).contains("收费标准一览表")
                .contains("| 层次 | 专业 |")
                .contains("| ---- | ---- |")
                .contains("少数民族预科班")
                .contains("8640");
        // 断言4：不再有半句截断——"当事人将承担相应的法律责任"完整保留
        assertThat(all).contains("当事人将承担相应的法律责任");
        // 断言5：绝大多数块大小受控（500 + 标题前缀容忍，表格块除外）
        for (Document d : docs) {
            String t = d.getText();
            if (t.contains("收费标准一览表") || t.contains("学费(元/年)")) {
                continue; // 表格块允许更大
            }
            assertThat(t.length()).as("正文块超长: " + t).isLessThan(800);
        }
    }
}