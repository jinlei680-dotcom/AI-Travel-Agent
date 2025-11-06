package com.aitravel.planner.service;

import com.aitravel.planner.itinerary.DayPlan;
import com.aitravel.planner.itinerary.ItineraryPlan;
import com.aitravel.planner.itinerary.Poi;
import com.aitravel.planner.itinerary.Route;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    @Value("${llm.openai.apiKey:}")
    private String openaiApiKey;

    @Value("${llm.openai.baseUrl:https://api.openai.com}")
    private String openaiBaseUrl;

    @Value("${llm.openai.model:gpt-4o-mini}")
    private String openaiModel;

    @Value("${llm.openai.systemPrompt:你是一位资深旅游规划师（Senior Travel Planner），请严格以 JSON 输出，不要包含任何解释、反引号或 Markdown。\n\n目标：根据用户提供的信息，为其生成包含交通、住宿、景点、餐厅等详细信息的分日旅游路线。\n\n输出格式（键名必须完全匹配）：\n{\n  \"cityCenter\": [lng, lat],\n  \"days\": [\n    {\n      \"summary\": \"字符串，概述当天的交通（地铁/步行/出租车等）、住宿建议（区域/价格带）、主要景点与餐厅安排、时间顺序与注意事项\",\n      \"routes\": [\n        { \"polyline\": \"lng,lat;lng,lat;...\", \"color\": \"#3b82f6\" }\n      ],\n      \"pois\": [\n        { \"name\": \"故宫博物院\", \"coord\": [lng, lat], \"type\": \"sight|museum|restaurant|hotel\" }\n      ]\n    }\n  ]\n}\n\n要求：\n- 坚持纯 JSON；不要输出任何多余文本。\n- 每天至少 4–6 个 POI，\"type\" 合理标注（如 sight/museum/restaurant/hotel）。\n- \"summary\" 需包含交通、住宿、景点与餐饮的细节建议与顺序。\n- 路线 \"polyline\" 可选，如生成则为 \"lng,lat;lng,lat;...\"；坐标为 GCJ-02 或接近的经纬度。\n- 若用户提供城市或时间、预算偏好，请在 \"summary\" 中体现。} ")
    private String systemPrompt;

    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    public Optional<ItineraryPlan> plan(String text, String city) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            log.warn("OPENAI API KEY 未配置，跳过 LLM 调用");
            return Optional.empty();
        }
        try {
            String url;
            // 兼容 DashScope（通义千问）与 OpenAI：
            // - DashScope 标准端点：https://dashscope.aliyuncs.com/api/v1/services/aigc/chat/completions
            // - DashScope OpenAI 兼容端点：https://dashscope.aliyuncs.com/compatible/v1/chat/completions
            // - 其它 OpenAI 兼容：<base>/v1/chat/completions 或 <base>/chat/completions 当 base 已含 /v1
            boolean isDashScope = openaiBaseUrl != null && openaiBaseUrl.contains("dashscope.aliyuncs.com");
            boolean isDashScopeCompatible = isDashScope && (
                    openaiBaseUrl.contains("/compatible") ||
                    openaiBaseUrl.contains("/compatible-mode")
            );
            // 某些 DashScope 模型（如 qwen-plus）在标准 REST 下更适配 text-generation
            boolean preferTextGeneration = isDashScope && !isDashScopeCompatible &&
                    (openaiModel != null && openaiModel.toLowerCase(Locale.ROOT).contains("qwen-plus"));
            if (isDashScopeCompatible) {
                // 走兼容路径
                if (openaiBaseUrl.endsWith("/v1") || openaiBaseUrl.endsWith("/v1/")) {
                    url = openaiBaseUrl + "/chat/completions";
                } else {
                    url = openaiBaseUrl + "/v1/chat/completions";
                }
            } else if (isDashScope) {
                // DashScope 标准：部分模型使用 text-generation 更稳妥
                url = preferTextGeneration
                        ? "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation"
                        : "https://dashscope.aliyuncs.com/api/v1/services/aigc/chat/completions";
            } else {
                // 其它 OpenAI 兼容实现
                if (openaiBaseUrl != null && (openaiBaseUrl.endsWith("/v1") || openaiBaseUrl.endsWith("/v1/"))) {
                    url = openaiBaseUrl + "/chat/completions";
                } else {
                    url = openaiBaseUrl + "/v1/chat/completions";
                }
            }
            Map<String, Object> body = new HashMap<>();
            body.put("model", openaiModel);
            String userContent = (city == null || city.isBlank()) ? text : (text + "\n城市:" + city);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            );

            if (isDashScope && !isDashScopeCompatible) {
                // DashScope 标准：chat 或 text-generation
                if (preferTextGeneration) {
                    // text-generation 使用 prompt 字段
                    String prompt = systemPrompt + "\n\n用户需求:" + userContent;
                    body.put("input", Map.of("prompt", prompt));
                    body.put("task", "text-generation");
                } else {
                    // chat/completions 使用 input.messages
                    body.put("input", Map.of("messages", messages));
                    body.put("task", "chat");
                }
                body.put("parameters", Map.of("result_format", "json"));
            } else {
                // OpenAI 兼容形态（包括 DashScope 兼容端点）
                body.put("messages", messages);
                body.put("response_format", Map.of("type", "json_object"));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);
            String jsonBody = mapper.writeValueAsString(body);
            log.debug("LLM POST {} model={}", url, openaiModel);
            log.debug("LLM req body: {}", jsonBody);
            HttpEntity<String> req = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> res = http.postForEntity(url, req, String.class);
            if (!res.getStatusCode().is2xxSuccessful()) {
                log.warn("OpenAI 非 2xx: {}", res.getStatusCode());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(res.getBody());
            // OpenAI 兼容优先，DashScope 标准/文本生成作为回退
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                content = root.path("output").path("choices").path(0).path("message").path("content").asText("");
            }
            if (content.isBlank()) {
                content = root.path("output").path("text").asText("");
            }
            if (content.isBlank()) {
                content = root.path("output_text").asText("");
            }
            if (content.isBlank()) {
                log.warn("OpenAI 返回空内容");
                return Optional.empty();
            }
            JsonNode planNode = mapper.readTree(content);
            ItineraryPlan plan = new ItineraryPlan();
            // cityCenter
            List<Double> center = new ArrayList<>();
            for (JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
            plan.setCityCenter(center.isEmpty() ? List.of(116.402, 39.907) : center);
            // days
            List<DayPlan> days = new ArrayList<>();
            for (JsonNode d : planNode.path("days")) {
                DayPlan day = new DayPlan();
                day.setSummary(d.path("summary").asText(null));
                List<Route> routes = new ArrayList<>();
                for (JsonNode r : d.path("routes")) {
                    Route rt = new Route();
                    rt.setPolyline(r.path("polyline").asText(null));
                    rt.setColor(r.path("color").asText(null));
                    routes.add(rt);
                }
                day.setRoutes(routes);
                List<Poi> pois = new ArrayList<>();
                for (JsonNode p : d.path("pois")) {
                    Poi poi = new Poi();
                    poi.setName(p.path("name").asText(null));
                    List<Double> coord = new ArrayList<>();
                    for (JsonNode c : p.path("coord")) { coord.add(c.asDouble()); }
                    poi.setCoord(coord);
                    poi.setType(p.path("type").asText(null));
                    pois.add(poi);
                }
                day.setPois(pois);
                days.add(day);
            }
            plan.setDays(days);
            return Optional.of(plan);
        } catch (Exception e) {
            log.warn("调用 LLM 失败: {}", e.toString());
            return Optional.empty();
        }
    }
}