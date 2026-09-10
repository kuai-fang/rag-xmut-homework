package com.wuyunbin.rag.config;

import com.wuyunbin.rag.service.FilePersistentChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring AI ChatClient 与多轮会话存储配置。
 */
@Configuration
public class ChatConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean
    public ChatMemory chatMemory(ObjectMapper objectMapper,
                                 @Value("${rag.chat.sessions-dir}") String sessionsDir,
                                 @Value("${rag.chat.memory.recent-messages:30}") int recentMessages) {
        return new FilePersistentChatMemory(objectMapper, sessionsDir, recentMessages);
    }

    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}