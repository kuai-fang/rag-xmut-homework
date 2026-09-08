package com.wuyunbin.rag.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 通过 Milvus RESTful HTTP API（v2/vectordb，端口 19530）查询知识库。
 * 用于"列出知识库集合、取知识库前 N 条向量化数据"等场景。
 */
@Service
public class MilvusQueryService {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int port;

    /**
     * 列出 Milvus 中的所有知识库集合。
     */
    public String listCollections() {
        return post("/v2/vectordb/collections/list", "{}");
    }

    /**
     * 查询指定集合中前 limit 条向量化数据（含 content 文本与 embedding 向量）。
     */
    public String topNVectors(String collection, int limit) {
        String body = "{\"collectionName\":\"" + collection + "\","
                + "\"limit\":" + limit + ","
                + "\"outputFields\":[\"doc_id\",\"content\",\"embedding\"]}";
        return post("/v2/vectordb/entities/query", body);
    }

    private String post(String path, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + host + ":" + port + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            throw new RuntimeException("调用 Milvus REST API 失败: " + e.getMessage(), e);
        }
    }
}