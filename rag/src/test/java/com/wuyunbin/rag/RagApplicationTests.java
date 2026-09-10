package com.wuyunbin.rag;

import com.wuyunbin.rag.test.StubChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上下文冒烟测试：排除 OpenAI/Milvus 自动配置并桩化依赖，
 * 使得加载 Spring 容器无需启动 LM Studio 与 Milvus。
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration,"
                + "org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration"
})
class RagApplicationTests {

    static final Path SESSIONS_DIR =
            Path.of(System.getProperty("java.io.tmpdir"), "rag-smoke-" + UUID.randomUUID());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("rag.chat.sessions-dir", SESSIONS_DIR::toString);
        registry.add("rag.chat.memory.recent-messages", () -> "30");
    }

    @TestConfiguration
    static class SmokeConfig {
        @Bean
        @Primary
        ChatClient.Builder chatClientBuilder() {
            return ChatClient.builder(new StubChatModel());
        }

        @Bean
        @Primary
        VectorStore vectorStore() {
            VectorStore vs = mock(VectorStore.class);
            when(vs.similaritySearch(any(SearchRequest.class))).thenReturn(java.util.List.of());
            return vs;
        }
    }

    @Test
    void contextLoads() {
    }

    static {
        try {
            Files.createDirectories(SESSIONS_DIR);
        } catch (Exception ignored) {
        }
    }
}