package com.mindbridge.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

/**
 * Ollama 请求拦截器：在 /api/chat 请求体中注入 think=false。
 *
 * <p>Qwen 3.5 默认开启思考模式，会消耗大量 token 导致实际回复为空。
 * 此拦截器拦截发往 Ollama 的 HTTP 请求，在 JSON body 中添加 "think":false。</p>
 */
@Component
public class OllamaThinkDisabler implements ClientHttpRequestInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ClientHttpResponse intercept(
            org.springframework.http.HttpRequest request,
            byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution
    ) throws IOException {
        if (request.getURI().getPath().contains("/api/chat") && body.length > 0) {
            try {
                ObjectNode node = (ObjectNode) objectMapper.readTree(body);
                if (!node.has("think")) {
                    node.put("think", false);
                    body = objectMapper.writeValueAsBytes(node);
                }
            } catch (Exception ignored) {
                // 解析失败则透传原始 body
            }
        }
        return execution.execute(request, body);
    }
}
