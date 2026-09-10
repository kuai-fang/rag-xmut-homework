package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.test.StubChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 集成测试：启动完整 Spring 容器，但排除 OpenAI/Milvus 自动配置，
 * 用桩 ChatModel + mock VectorStore 在不依赖 LM Studio / Milvus 的情况下
 * 走通 Controller -> Service -> Advisor -> 桩模型 全链路。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
                + "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
})
@AutoConfigureMockMvc
class ChatControllerIntegrationTest {

    static final Path SESSIONS_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "rag-it-" + UUID.randomUUID());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("rag.chat.sessions-dir", SESSIONS_DIR::toString);
        registry.add("rag.chat.memory.recent-messages", () -> "30");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class ChatITConfig {
        @Bean
        @Primary
        ChatClient.Builder chatClientBuilder() {
            return ChatClient.builder(new StubChatModel());
        }

        @Bean
        @Primary
        VectorStore vectorStore() {
            return mock(VectorStore.class);
        }
    }

    @BeforeEach
    void setUp() {
        reset(vectorStore);
    }

    private void stubHit(String content) {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(content, new HashMap<>(Map.of("source", "k.txt")))));
    }

    @Test
    void 多轮提问请求成功() throws Exception {
        stubHit("校史知识");
        String first = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"第一问\",\"ragEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").isNotEmpty())
                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String sessionId = objectMapper.readTree(first).get("sessionId").asText();

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"第二问\",\"sessionId\":\""
                                + sessionId + "\",\"ragEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));
    }

    @Test
    void 空message返回400统一错误体() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void 非法JSON返回400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-valid-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void 错误ContentType返回415() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("message=hi"))
                .andExpect(status().is(415))
                .andExpect(jsonPath("$.code").value(415));
    }

    @Test
    void 上游异常返回500统一错误体() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("milvus down"));
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\",\"ragEnabled\":true}"))
                .andExpect(status().is(500))
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void RAG命中时返回sources() throws Exception {
        stubHit("厦门理工学院校训");
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"校训是什么？\",\"ragEnabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources[0].content").value("厦门理工学院校训"));
    }

    @Test
    void 清空会话返回204且可重复调用() throws Exception {
        mvc.perform(delete("/api/chat/sessions/abc")).andExpect(status().isNoContent());
        mvc.perform(delete("/api/chat/sessions/abc")).andExpect(status().isNoContent());
    }

    @Test
    void 会话历史落盘到文件() throws Exception {
        String first = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"落盘测试\",\"ragEnabled\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sid = objectMapper.readTree(first).get("sessionId").asText();
        Path file = SESSIONS_DIR.resolve(sid + ".json");
        org.assertj.core.api.Assertions.assertThat(Files.exists(file)).isTrue();
    }
}