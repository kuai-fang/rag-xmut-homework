package com.wuyunbin.rag.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.FileNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler 单元测试：IOException 映射为 400 可读错误、兜底映射 500、path 填充。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest request(String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        return req;
    }

    @Test
    void io异常返回400与可读消息并填充路径() {
        HttpServletRequest req = request("/api/rag/import");
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
                handler.handleIo(new FileNotFoundException("文件不存在或不可读"), req);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().message()).contains("文件不存在或不可读");
        assertThat(resp.getBody().path()).isEqualTo("/api/rag/import");
    }

    @Test
    void 兜底异常返回500并填充路径() {
        HttpServletRequest req = request("/api/chat");
        ResponseEntity<GlobalExceptionHandler.ErrorBody> resp =
                handler.handleGeneric(new IllegalStateException("boom"), req);

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().message()).isEqualTo("服务处理失败");
        assertThat(resp.getBody().path()).isEqualTo("/api/chat");
    }
}