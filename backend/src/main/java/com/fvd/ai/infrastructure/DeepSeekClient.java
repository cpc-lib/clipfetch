package com.fvd.ai.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fvd.shared.web.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI Chat 客户端（OpenAI 兼容协议，流式输出，当前接入通义千问 Qwen）
 */
@Slf4j
@Service
public class DeepSeekClient {

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekClient(@Value("${app.ai.base-url}") String baseUrl,
                          @Value("${app.ai.api-key:}") String apiKey,
                          @Value("${app.ai.model}") String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 流式对话：onToken 逐 token 回调，返回完整文本
     */
    public String streamChat(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        if (!available()) {
            throw new BusinessException("AI 功能未配置，请在 backend/.env 中填写 AI_API_KEY");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                ),
                "stream", true
        );
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new BusinessException("构建 AI 请求失败");
        }

        StringBuilder full = new StringBuilder();
        try {
            HttpResponse<java.io.InputStream> resp =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String err = new String(resp.body().readAllBytes());
                log.warn("AI 接口返回 {}: {}", resp.statusCode(), err);
                throw new BusinessException("AI 服务异常（" + resp.statusCode() + "），请稍后重试");
            }
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode node = mapper.readTree(data);
                    String token = node.path("choices").path(0).path("delta").path("content").asText("");
                    if (!token.isEmpty()) {
                        full.append(token);
                        if (onToken != null) {
                            onToken.accept(token);
                        }
                    }
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("AI 服务连接失败：" + e.getMessage());
        }
        return full.toString();
    }
}
