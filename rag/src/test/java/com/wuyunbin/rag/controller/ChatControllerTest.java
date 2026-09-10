package com.wuyunbin.rag.controller;

import com.wuyunbin.rag.dto.ChatResponse;
import com.wuyunbin.rag.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ChatController 单元测试：MockMvc + Mock ChatService，验证 Web 层参数校验与路径映射。
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChatService chatService;

    @Test
    void 合法请求返回200() throws Exception {
        when(chatService.chat(any()))
                .thenReturn(ChatResponse.of("ok", "sid1", null));

        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"message": "请问厦门理工学院创建于哪一年？"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("ok"))
                .andExpect(jsonPath("$.sessionId").value("sid1"));
    }

    @Test
    void 空message返回400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"message": "  "}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 缺失message返回400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"sessionId": "123"}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topK越界返回400() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"message": "你好", "topK": 25}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {"message": "你好", "topK": 0}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 错误ContentType返回415() throws Exception {
        mvc.perform(post("/api/chat")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("message=hi"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void 删除会话返回204() throws Exception {
        doNothing().when(chatService).clearSession("sidToClear");

        mvc.perform(delete("/api/chat/sessions/sidToClear"))
                .andExpect(status().isNoContent());
    }

    @Test
    void topK在范围内不触发校验错误() throws Exception {
        when(chatService.chat(any())).thenReturn(ChatResponse.of("ok", "sid", null));
        for (int k : new int[]{1, 5, 20}) {
            mvc.perform(post("/api/chat")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(String.format("""
                            {"message": "hi", "topK": %d}
                            """, k)))
                    .andExpect(status().isOk());
        }
    }
}