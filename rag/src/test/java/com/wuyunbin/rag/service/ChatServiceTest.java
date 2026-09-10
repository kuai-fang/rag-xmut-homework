package com.wuyunbin.rag.service;

import com.wuyunbin.rag.dto.ChatRequest;
import com.wuyunbin.rag.dto.ChatResponse;
import com.wuyunbin.rag.test.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wuyunbin.rag.test.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatService 单元测试：真实 ChatClient + 桩模型 + 临时文件会话存储，
 * VectorStore 用 mock，RagKnowledgeService 走真实检索映射逻辑。
 */
class ChatServiceTest {

    private Path tempDir;
    private VectorStore vectorStore;
    private RagKnowledgeService ragKnowledge;
    private ChatService chatService;
    private StubChatModel stubModel;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("chat-session-test");
        stubModel = new StubChatModel();
        ChatClient chatClient = ChatClient.builder(stubModel).build();
        ChatMemory memory = new FilePersistentChatMemory(new tools.jackson.databind.ObjectMapper(), tempDir.toString(), 100);
        MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(memory).build();

        vectorStore = mock(VectorStore.class);
        ragKnowledge = new RagKnowledgeService(vectorStore);
        chatService = new ChatService(chatClient, memory, ragKnowledge, advisor);
    }

    private void stubHits(String content) {
        Document doc = new Document(content, new HashMap<>(Map.of("source", "t.txt")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(doc));
    }

    @Test
    void 多轮提问记住前一轮() {
        stubHits("无关知识");
        stubModel.reset();
        String sid = chatService.chat(new ChatRequest("第一问", null, false, 5)).sessionId();

        stubModel.reset();
        chatService.chat(new ChatRequest("第二问", sid, false, 5));

        String second = stubModel.lastPrompt().getContents();
        assertThat(second).contains("第一问");
        assertThat(second).contains("第二问");
    }

    @Test
    void 新会话自动生成会话ID() {
        ChatResponse r1 = chatService.chat(new ChatRequest("你好", null, false, 5));
        ChatResponse r2 = chatService.chat(new ChatRequest("你好", null, false, 5));
        assertThat(r1.sessionId()).isNotBlank();
        assertThat(r2.sessionId()).isNotBlank();
        assertThat(r1.sessionId()).isNotEqualTo(r2.sessionId());
    }

    @Test
    void 不同会话历史互不串扰() {
        String sid1 = chatService.chat(new ChatRequest("我在会话一说的秘密", null, false, 5)).sessionId();
        String sid2 = chatService.chat(new ChatRequest("会话二第一条", null, false, 5)).sessionId();
        assertThat(sid1).isNotEqualTo(sid2);

        stubModel.reset();
        chatService.chat(new ChatRequest("继续说", sid2, false, 5));
        String prompt2 = stubModel.lastPrompt().getContents();
        assertThat(prompt2).contains("会话二第一条");
        assertThat(prompt2).doesNotContain("我在会话一说的秘密");
    }

    @Test
    void 关闭RAG时不注入检索上下文() {
        stubHits("某未被引用的知识");
        stubModel.reset();
        chatService.chat(new ChatRequest("问题", "s1", false, 5));
        assertThat(stubModel.lastPrompt().getContents()).doesNotContain("某未被引用的知识");
    }

    @Test
    void 开启RAG且命中时返回sources() {
        stubHits("知识内容甲");
        ChatResponse r = chatService.chat(new ChatRequest("问题", null, true, 5));
        assertThat(r.sources()).extracting("content").contains("知识内容甲");
    }

    @Test
    void 开启RAG但未命中时仍回复且sources为空() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        ChatResponse r = chatService.chat(new ChatRequest("问题", null, true, 5));
        assertThat(r.reply()).isNotBlank();
        assertThat(r.sources()).isEmpty();
    }

    @Test
    void 模型异常时向上抛出() {
        ChatClient broken = ChatClient.builder(new StubChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                throw new RuntimeException("上游炸了");
            }
        }).build();
        ChatService svc = new ChatService(broken, mock(ChatMemory.class), ragKnowledge,
                MessageChatMemoryAdvisor.builder(mock(ChatMemory.class)).build());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.chat(new ChatRequest("问", null, false, 5)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("上游炸了");
    }

    @Test
    void 清空会话后历史不再注入() {
        String sid = chatService.chat(new ChatRequest("记住这轮吧", null, false, 5)).sessionId();
        chatService.clearSession(sid);

        stubModel.reset();
        chatService.chat(new ChatRequest("新的一轮", sid, false, 5));
        assertThat(stubModel.lastPrompt().getContents()).doesNotContain("记住这轮吧");
    }

    @Test
    void 历史超过窗口时只注入最近N条() {
        ChatMemory small = new FilePersistentChatMemory(new tools.jackson.databind.ObjectMapper(), tempDir.toString(), 3);
        ChatService svc = new ChatService(ChatClient.builder(stubModel).build(), small,
                ragKnowledge, MessageChatMemoryAdvisor.builder(small).build());
        String sid = "sess";
        svc.chat(new ChatRequest("msg1", sid, false, 5));
        stubModel.reset();
        svc.chat(new ChatRequest("msg2", sid, false, 5));
        stubModel.reset();
        svc.chat(new ChatRequest("msg3", sid, false, 5));

        stubModel.reset();
        svc.chat(new ChatRequest("msg4", sid, false, 5));
        String p = stubModel.lastPrompt().getContents();
        // 窗口=3：msg4 之前只应保留最近 3 条（msg1 被挤出）
        assertThat(p).contains("msg4");
        assertThat(p).doesNotContain("msg1");
    }

    @Test
    void chatMemory存储的真人消息可被get还原() {
        FilePersistentChatMemory m = new FilePersistentChatMemory(
                new tools.jackson.databind.ObjectMapper(), tempDir.toString(), 100);
        m.add("c1", List.of(new UserMessage("hi")));
        List<org.springframework.ai.chat.messages.Message> got = m.get("c1");
        assertThat(got).hasSize(1);
        assertThat(got.get(0).getText()).isEqualTo("hi");
        assertThat(Files.exists(tempDir.resolve("c1.json"))).isTrue();
    }

    @Test
    void 脱敏手机号保留前3后4() {
        assertThat(ChatService.mask("联系电话13812340000转分机"))
                .contains("138****0000")
                .doesNotContain("13812340000");
    }

    @Test
    void 脱敏邮箱保留头部与域名() {
        String out = ChatService.mask("联系 alex.wang@example.com 获取");
        assertThat(out).contains("a***@example.com")
                .doesNotContain("alex.wang@example.com");
    }

    @Test
    void 脱敏URL仅保留协议与域名() {
        String out = ChatService.mask("详见 https://docs.example.com/a/b?q=1");
        assertThat(out).contains("https://docs.example.com/***")
                .doesNotContain("/a/b");
    }

    @Test
    void mask对null返回null() {
        assertThat(ChatService.mask(null)).isNull();
    }

    @Test
    void 超限SystemPrompt保头保尾截断() {
        String input = "头部内容".repeat(200); // 1000 字符
        String marker = "\n...[中间内容因超限已截断]...\n";
        String out = ChatService.enforceInjectLimit("s1", input, 200);
        assertThat(out).hasSize(200 + marker.length())
                .contains("...[中间内容因超限已截断]...");
        // 保头：以头部原文开头；保尾：以尾部原文结尾
        assertThat(out).startsWith(input.substring(0, 100))
                .endsWith(input.substring(input.length() - 100));
    }

    @Test
    void 未超限SystemPrompt原样返回() {
        String input = "普通知识".repeat(20);
        assertThat(ChatService.enforceInjectLimit("s1", input, 10_000)).isEqualTo(input);
        assertThat(ChatService.enforceInjectLimit("s1", null, 100)).isNull();
    }

    @Test
    void 检索日志打印命中文档全文与分数() {
        stubHits("厦门理工校史片段：成立于1981年。");
        ListAppender.attachLogger(ChatService.class);
        String sid = chatService.chat(new ChatRequest("校史？", null, true, 5)).sessionId();

        List<String> msgs = ListAppender.getFormattedMessages();
        assertThat(msgs).anyMatch(m -> m.contains("[rag][") && m.contains("命中=1"));
        assertThat(msgs).anyMatch(m -> m.contains("RAG 召回文档"));
        assertThat(msgs).anyMatch(m -> m.contains("召回数量"));
        assertThat(msgs).anyMatch(m -> m.contains("source=t.txt"));
        assertThat(msgs).anyMatch(m -> m.contains("【正文】"));
        assertThat(msgs).anyMatch(m -> m.contains("厦门理工校史片段：成立于1981年。"));
        assertThat(msgs).anyMatch(m -> m.contains("[chat][") && m.contains("注入模型"));
        ListAppender.detachLogger();
    }

    @Test
    void 结构化块包含多篇doc与metadata() {
        Map<String, Object> meta1 = new HashMap<>(Map.of("source", "a.txt", "chunk_index", 0));
        Map<String, Object> meta2 = new HashMap<>(Map.of("source", "b.txt", "chunk_index", 3));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                new Document("片段一二三", meta1),
                new Document("片段四五六", meta2)));
        ListAppender.attachLogger(ChatService.class);
        chatService.chat(new ChatRequest("测试", null, true, 5)).sessionId();

        String block = ListAppender.getFormattedMessages().stream()
                .filter(m -> m.contains("RAG 召回文档"))
                .findFirst().orElse("");
        assertThat(block)
                .contains("conversationId")
                .contains("召回数量")
                .contains("---- doc[0]")
                .contains("---- doc[1]")
                .contains("chunk_index=0")
                .contains("chunk_index=3")
                .contains("source=a.txt")
                .contains("source=b.txt")
                .contains("END (共 2 篇");
        ListAppender.detachLogger();
    }
}