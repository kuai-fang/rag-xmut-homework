package com.wuyunbin.rag.service;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于文件的多轮会话存储。
 * <p>
 * 全量消息以 JSON 持久化到 {@code sessions/{sessionId}.json}；
 * 读取(注入提示词)时按 {@code rag.chat.memory.recent-messages} 只返回最近 N 条，
 * 以满足「文件存全部、提示词只发最近 N 条」的多轮上下文控制。
 */
public class FilePersistentChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(FilePersistentChatMemory.class);

    private final ObjectMapper objectMapper;
    private final Path sessionsDir;
    private final int recentMessages;

    /** 运行期缓存：会话ID -> 该会话全量消息。重启后首次 get 时从文件回填。 */
    private final Map<String, List<Message>> cache = new ConcurrentHashMap<>();

    public FilePersistentChatMemory(ObjectMapper objectMapper, String sessionsDir, int recentMessages) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.sessionsDir = Path.of(sessionsDir);
        this.recentMessages = Math.max(1, recentMessages);
        try {
            Files.createDirectories(this.sessionsDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话目录: " + this.sessionsDir, e);
        }
    }

    private Path fileOf(String conversationId) {
        return sessionsDir.resolve(conversationId + ".json");
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        List<Message> merged = cache.compute(conversationId, (k, existing) -> {
            List<Message> next = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            next.addAll(messages);
            return next;
        });
        persist(conversationId, merged);
    }

    @Override
    public List<Message> get(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<Message> all = cache.computeIfAbsent(conversationId, this::loadFromFile);
        int from = Math.max(0, all.size() - recentMessages);
        return new ArrayList<>(all.subList(from, all.size()));
    }

    @Override
    public void clear(String conversationId) {
        if (conversationId == null) {
            return;
        }
        cache.remove(conversationId);
        try {
            Files.deleteIfExists(fileOf(conversationId));
        } catch (IOException e) {
            log.warn("清空会话文件失败: {}", conversationId, e);
        }
    }

    private List<Message> loadFromFile(String conversationId) {
        Path file = fileOf(conversationId);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            StoredMessage[] stored = objectMapper.readValue(json, StoredMessage[].class);
            List<Message> result = new ArrayList<>();
            for (StoredMessage s : stored) {
                Message m = hydrate(s);
                if (m != null) {
                    result.add(m);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("读取会话文件失败，采用内存缓存: {}", conversationId, e);
            return cache.getOrDefault(conversationId, new ArrayList<>());
        }
    }

    private void persist(String conversationId, List<Message> messages) {
        try {
            List<StoredMessage> stored = new ArrayList<>(messages.size());
            for (Message m : messages) {
                stored.add(new StoredMessage(m.getMessageType().getValue(), m.getText()));
            }
            Files.writeString(fileOf(conversationId), objectMapper.writeValueAsString(stored), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 持久化失败不阻断对话，内存缓存仍可用
            log.warn("持久化会话失败: {}", conversationId, e);
        }
    }

    private Message hydrate(StoredMessage s) {
        try {
            if (s == null || s.role() == null) {
                return null;
            }
            return switch (MessageType.valueOf(s.role())) {
                case USER -> new UserMessage(s.content());
                case ASSISTANT -> new AssistantMessage(s.content());
                case SYSTEM -> new SystemMessage(s.content());
                default -> null;
            };
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 磁盘上的简单结构，避免 Spring AI 消息类的多态序列化复杂度。 */
    record StoredMessage(String role, String content) {
    }
}