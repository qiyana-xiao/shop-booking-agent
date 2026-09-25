package com.shopbooking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopbooking.config.AppProperties;
import com.shopbooking.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Chat Completions 封装：原生 tools[] 调用（不依赖任何智能体框架）。
 * 超时/5xx 重试一次（退避 500ms）；密钥未配置或仍失败抛 DeepSeekUnavailableException，由上层降级。
 */
@Service
public class DeepSeekService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    public record ToolCall(String id, String name, Map<String, Object> arguments) {
    }

    public record ChatResponse(String content, List<ToolCall> toolCalls, long totalTokens) {
    }

    public static class DeepSeekUnavailableException extends RuntimeException {
        public DeepSeekUnavailableException(String message) {
            super(message);
        }
    }

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final AiKeyService aiKeyService;
    private RestClient client;

    public DeepSeekService(AppProperties props, ObjectMapper objectMapper, AiKeyService aiKeyService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.aiKeyService = aiKeyService;
    }

    /** 不含 Authorization 头（密钥支持界面动态配置，每次调用时单独携带） */
    private RestClient client() {
        if (client == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(props.getDeepseek().getConnectTimeoutMs());
            factory.setReadTimeout(props.getDeepseek().getReadTimeoutMs());
            client = RestClient.builder()
                    .baseUrl(props.getDeepseek().getBaseUrl())
                    .defaultHeader("Content-Type", "application/json")
                    .requestFactory(factory)
                    .build();
        }
        return client;
    }

    public boolean configured() {
        return aiKeyService.configured();
    }

    @SuppressWarnings("unchecked")
    public ChatResponse chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        String apiKey = aiKeyService.effectiveKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new DeepSeekUnavailableException(
                    "AI 密钥未配置：请由老板登录后在「店铺设置 → AI 客服设置」中填写 DeepSeek API Key");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getDeepseek().getModel());
        body.put("messages", messages);
        body.put("stream", false);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        RuntimeException lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new DeepSeekUnavailableException("请求被中断");
                }
            }
            try {
                String raw = client().post().uri("/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .body(body).retrieve().body(String.class);
                return parse(raw);
            } catch (ResourceAccessException e) {
                lastError = e;
                log.warn("DeepSeek 网络异常（第 {} 次）：{}", attempt + 1, e.getMessage());
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is5xxServerError()) {
                    lastError = e;
                    log.warn("DeepSeek 5xx（第 {} 次）：{} {}", attempt + 1, e.getStatusCode(), e.getResponseBodyAsString());
                } else {
                    throw new DeepSeekUnavailableException("DeepSeek 调用失败：" + e.getStatusCode().value());
                }
            } catch (DeepSeekUnavailableException e) {
                throw e;
            } catch (Exception e) {
                throw new DeepSeekUnavailableException("DeepSeek 响应解析失败：" + e.getMessage());
            }
        }
        throw new DeepSeekUnavailableException("DeepSeek 暂时不可用：" + lastError.getMessage());
    }

    private ChatResponse parse(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choice = root.path("choices").path(0).path("message");
            String content = choice.path("content").asText(null);

            List<ToolCall> toolCalls = new ArrayList<>();
            JsonNode calls = choice.path("tool_calls");
            if (calls.isArray()) {
                for (JsonNode call : calls) {
                    String id = call.path("id").asText();
                    String name = call.path("function").path("name").asText();
                    String argsJson = call.path("function").path("arguments").asText("{}");
                    Map<String, Object> args = objectMapper.readValue(argsJson,
                            objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
                    toolCalls.add(new ToolCall(id, name, args));
                }
            }
            // 兜底：个别情况下模型把工具调用写成 Markdown 围栏 JSON 文本
            if (toolCalls.isEmpty() && content != null && content.contains("\"name\"") && content.contains("\"arguments\"")) {
                ToolCall fenced = parseFencedToolCall(content);
                if (fenced != null) {
                    toolCalls.add(fenced);
                    content = null;
                }
            }
            long totalTokens = root.path("usage").path("total_tokens").asLong(0);
            return new ChatResponse(content, toolCalls, totalTokens);
        } catch (DeepSeekUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new DeepSeekUnavailableException("DeepSeek 响应解析失败：" + e.getMessage());
        }
    }

    /** 清洗 Markdown 围栏后按工具调用解析，失败返回 null */
    private ToolCall parseFencedToolCall(String content) {
        try {
            String cleaned = content.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
            }
            JsonNode node = objectMapper.readTree(cleaned);
            String name = node.path("name").asText(null);
            if (name == null || name.isBlank()) {
                return null;
            }
            Map<String, Object> args = objectMapper.convertValue(node.path("arguments"),
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            return new ToolCall("fenced-" + System.currentTimeMillis(), name, args);
        } catch (Exception e) {
            return null;
        }
    }

    /** 滚动摘要压缩（独立小请求，失败返回 null 由调用方保留旧摘要） */
    public String summarize(String previousSummary, List<ChatMessage> dropped) {
        if (!configured()) {
            return null;
        }
        StringBuilder transcript = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            transcript.append("[此前摘要]\n").append(previousSummary).append("\n\n");
        }
        transcript.append("[新增对话]\n");
        for (ChatMessage m : dropped) {
            transcript.append("user".equals(m.getRole()) ? "顾客：" : "AI：")
                    .append(m.getContent()).append("\n");
        }
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "system", "content",
                        "你是对话摘要助手。把顾客与店小约 AI 客服的对话压缩成不超过 300 字的摘要，" +
                                "重点保留：顾客的需求（人数/时间/服务偏好）、已完成的操作（锁座/确认/改期/取消及预约单号）、" +
                                "顾客留下的联系方式和特殊要求。只输出摘要正文。"),
                Map.of("role", "user", "content", transcript.toString()));
        try {
            ChatResponse resp = chat(messages, null);
            return resp.content();
        } catch (Exception e) {
            log.warn("摘要请求失败：{}", e.getMessage());
            return null;
        }
    }
}
