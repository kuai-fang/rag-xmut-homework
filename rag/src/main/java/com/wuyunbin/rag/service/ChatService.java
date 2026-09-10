package com.wuyunbin.rag.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.wuyunbin.rag.dto.ChatRequest;
import com.wuyunbin.rag.dto.ChatResponse;
import com.wuyunbin.rag.dto.SearchHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 多轮知识增强问答服务。
 * <p>
 * 调用前：按需从知识库检索相关知识作为 SystemMessage 注入；多轮历史由
 * {@code MessageChatMemoryAdvisor} 从文件会话存储中取出最近 N 条注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RagKnowledgeService ragKnowledgeService;
    private final MessageChatMemoryAdvisor chatMemoryAdvisor;

    /** 日志脱敏开关：开启后打印命中文档时对手机号/邮箱/URL 做脱敏。 */
    @Value("${rag.log.mask:false}")
    private boolean logMask = false;

    /** 注入模型的 System Prompt 上限字符数（默认约 1M，衔接“上下文范围 1M”）。 */
    @Value("${rag.log.max-inject-chars:1000000}")
    private int maxInjectChars = 1_000_000;

    /** 结构化输出块中每篇召回文档最多打印的字符数（超长截断）。 */
    @Value("${rag.log.max-content-chars:100000}")
    private int maxContentChars = 100_000;

    /**
     * 多轮问答：可选结合知识库检索 + 会话记忆。
     *
     * @param request 多轮聊天请求
     * @return 多轮聊天响应（含会话 ID 与命中的知识片段）
     */
    public ChatResponse chat(ChatRequest request) {
        String sessionId = resolveSessionId(request.sessionId());
        boolean rag = request.isRagEnabledOrDefault();
        int topK = request.resolvedTopK();

        log.debug("[chat][{}] 收到请求  ragEnabled={} topK={} message={}",
                sessionId, rag, topK, request.message());

        List<SearchHit> sources = null;
        String systemContext = null;
        if (rag) {
            long t0 = System.currentTimeMillis();
            sources = ragKnowledgeService.search(request.message(), topK);
            long costMs = System.currentTimeMillis() - t0;
            logRetrieval(sessionId, request.message(), sources, costMs);

            systemContext = sources.stream()
                    .map(SearchHit::content)
                    .collect(Collectors.joining("\n"));
            systemContext = enforceInjectLimit(sessionId, systemContext, maxInjectChars);
        }

        ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                .advisors(a -> a.advisors(chatMemoryAdvisor)
                        .param(ChatMemory.CONVERSATION_ID, sessionId));
        if (systemContext != null && !systemContext.isBlank()) {
            log.debug("[chat][{}] 注入模型 System Prompt: {} 字符", sessionId, systemContext.length());
            spec = spec.system(systemContext);
        }
        String reply = spec.user(request.message()).call().content();
        return ChatResponse.of(reply, sessionId, sources);
    }

    /** 清空某个会话的历史（幂等，会话不存在也视为成功）。 */
    public void clearSession(String sessionId) {
        chatMemory.clear(sessionId);
    }

    /**
     * 打印完整的检索明细：先输出一行性能摘要，再输出截图风格的结构化召回块
     * （含 conversationId / query / 召回数量 / 编号 doc 条目 / END 尾部）。
     */
    private void logRetrieval(String sessionId, String query, List<SearchHit> hits, long costMs) {
        if (hits == null || hits.isEmpty()) {
            log.warn("[rag][{}] 检索未命中任何文档  查询={} topK 为空召回", sessionId, safeText(query));
            return;
        }
        List<Double> scores = hits.stream()
                .map(SearchHit::score)
                .filter(s -> s != null)
                .toList();
        Double min = scores.stream().min(Comparator.naturalOrder()).orElse(null);
        Double max = scores.stream().max(Comparator.naturalOrder()).orElse(null);
        log.debug("[rag][{}] 检索完成  命中={} 耗时={}ms 评分区间=[{} ~ {}]",
                sessionId, hits.size(), costMs, formatScore(min), formatScore(max));
        log.debug("{}", formatRetrievalBlock(sessionId, query, hits));
    }

    /** 构造截图风格的结构化召回块；块内多行无逐行日志前缀，便于阅读与归档。 */
    private String formatRetrievalBlock(String sessionId, String query, List<SearchHit> hits) {
        StringBuilder b = new StringBuilder();
        b.append("\n").append("=".repeat(18)).append(" RAG 召回文档 ").append("=".repeat(18)).append("\n");
        b.append(padField("conversationId")).append(sessionId).append("\n");
        b.append(padField("query")).append(safeText(query)).append("\n");
        b.append(padField("召回数量")).append(hits.size()).append("\n");

        for (int i = 0; i < hits.size(); i++) {
            SearchHit hit = hits.get(i);
            Map<String, Object> meta = hit.metadata();
            String content = hit.content() == null ? "" : hit.content();
            if (logMask) {
                content = mask(content);
            }
            if (content.length() > maxContentChars) {
                content = content.substring(0, maxContentChars) + "...[截断]";
            }
            b.append("---- doc[").append(i).append("] ").append("id=").append(docId(meta, i))
                    .append(", metadata=").append(formatMeta(meta, hit.score())).append("\n");
            b.append("      【正文】").append(content).append("\n");
        }
        b.append("=".repeat(12)).append(" END (共 ").append(hits.size())
                .append(" 篇，每篇打印 ").append(maxContentChars).append(" 字符) ").append("=".repeat(12)).append("\n");
        return b.toString();
    }

    private static String padField(String field) {
        int width = 14; // conversationId 长度对齐
        int pad = Math.max(1, width - displayWidth(field));
        return field + " ".repeat(pad) + ": ";
    }

    /** 中文等全角字符按显示宽度计为 2。 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += (s.charAt(i) >= 0x2E80) ? 2 : 1;
        }
        return w;
    }

    private static String docId(Map<String, Object> meta, int index) {
        Object id = meta == null ? null : meta.get("id");
        return id != null ? String.valueOf(id) : "doc-" + index;
    }

    private static String formatMeta(Map<String, Object> meta, Double score) {
        String distance = "-";
        String chunkIndex = null;
        String source = null;
        if (meta != null) {
            if (meta.get("distance") != null) {
                distance = String.format("%.15f", asDouble(meta.get("distance")));
            } else if (score != null) {
                distance = formatScore(score);
            }
            Object ci = meta.get("chunk_index");
            chunkIndex = ci == null ? null : String.valueOf(ci);
            Object src = meta.get("source");
            source = src == null ? null : String.valueOf(src);
        }
        return "{distance=" + distance + ", chunk_index=" + chunkIndex + ", source=" + source + "}";
    }

    private static double asDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(String.valueOf(o));
    }

    /**
     * System Prompt 超限保护：超过 limit 时打 WARN 并保头保尾截断，
     * 避免注入超上下文导致模型报错。
     */
    static String enforceInjectLimit(String sessionId, String context, int limit) {
        if (context == null) {
            return null;
        }
        if (context.length() <= limit) {
            return context;
        }
        log.warn("[rag][{}] System Prompt 超限  total={} 超过上限 {}，已保头保尾截断",
                sessionId, context.length(), limit);
        int keepEach = limit / 2;
        return context.substring(0, keepEach)
                + "\n...[中间内容因超限已截断]...\n"
                + context.substring(context.length() - keepEach);
    }

    /** 敏感信息脱敏：手机号(11位数字)、邮箱、URL。仅用于日志打印，不影响注入原文。 */
    static String mask(String text) {
        if (text == null) {
            return null;
        }
        String masked = text.replaceAll("\\b(1[3-9]\\d)(\\d{4})(\\d{4})\\b", "$1****$3");
        // 邮箱：保留用户名首字符与域名
        masked = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                .matcher(masked).replaceAll(mr -> maskEmail(mr.group()));
        // URL：保留协议与域名
        masked = Pattern.compile("(?i)https?://[^\\s]+")
                .matcher(masked).replaceAll(mr -> maskUrl(mr.group()));
        return masked;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        return email.charAt(0) + "***@" + email.substring(at + 1);
    }

    private static String maskUrl(String url) {
        int slash = url.indexOf('/', 8); // 跳过 scheme://
        String base = slash < 0 ? url : url.substring(0, slash);
        return base + "/***";
    }

    private static String formatScore(Double score) {
        if (score == null) {
            return "-";
        }
        return String.format("%.4f", score);
    }

    /** 查询文本可能较长，取前 120 字符便于日志阅读。 */
    private static String safeText(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    private String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }
}