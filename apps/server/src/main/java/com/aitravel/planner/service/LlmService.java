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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@Service
public class LlmService {
    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    @Value("${llm.openai.apiKey:}")
    private String openaiApiKey;

    @Value("${llm.openai.baseUrl:https://api.openai.com}")
    private String openaiBaseUrl;

    @Value("${llm.openai.model:gpt-4o-mini}")
    private String openaiModel;

    @Value("${llm.openai.systemPrompt}")
    private String systemPrompt;

    private RestTemplate http;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.http.connectTimeoutMs:3000}")
    private int httpConnectTimeoutMs;

    @Value("${llm.http.readTimeoutMs:15000}")
    private int httpReadTimeoutMs;

    public LlmService() {
        // 先用默认值初始化，@PostConstruct 再根据配置覆盖
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.http = new RestTemplate(factory);
    }

    @PostConstruct
    public void initHttp() {
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(httpConnectTimeoutMs);
            factory.setReadTimeout(httpReadTimeoutMs);
            this.http = new RestTemplate(factory);
            log.info("LLM HTTP 超时: connect={}ms, read={}ms", httpConnectTimeoutMs, httpReadTimeoutMs);
        } catch (Exception e) {
            log.warn("初始化 LLM HTTP 超时失败，沿用默认: {}", e.toString());
        }
    }

    // 同步产出：结构化计划 + 原始文本
    public static class PlanResult {
        private ItineraryPlan plan;
        private String rawText;

        public PlanResult() {}
        public PlanResult(ItineraryPlan plan, String rawText) {
            this.plan = plan;
            this.rawText = rawText;
        }
        public ItineraryPlan getPlan() { return plan; }
        public void setPlan(ItineraryPlan plan) { this.plan = plan; }
        public String getRawText() { return rawText; }
        public void setRawText(String rawText) { this.rawText = rawText; }
    }

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
                // DashScope 标准：使用 text-generation/generation 更稳妥
                url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
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
            String userContentBase = (city == null || city.isBlank()) ? text : (text + "\n城市:" + city);
            String navHint = "\n\n请严格以 JSON 输出，并包含每日导航信息：cityCenter, days[].summary, days[].routes[].polyline/color, days[].pois[].name/coord/type。" +
                    "\n路线 polyline 采用 \"lng,lat;lng,lat;...\" 格式，经纬度为 GCJ-02 或接近值。";
            String userContent = userContentBase + navHint;
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            );

            if (isDashScope && !isDashScopeCompatible) {
                // 标准 text-generation：使用 input.prompt + parameters.result_format
                String prompt = systemPrompt + "\n\n用户需求:" + userContent;
                body.put("input", Map.of("prompt", prompt));
                body.put("parameters", Map.of("result_format", "text"));
            } else {
                // OpenAI 兼容形态（包括 DashScope 兼容端点）：直接返回文本消息内容
                body.put("messages", messages);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);
            String jsonBody = mapper.writeValueAsString(body);
            log.debug("LLM POST {} model={}", url, openaiModel);
            log.debug("LLM req body: {}", jsonBody);
            HttpEntity<String> req = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> res;
            try {
                res = http.postForEntity(url, req, String.class);
            } catch (Exception callErr) {
                // 标准端点失败时，回退到 DashScope 兼容模式（OpenAI 兼容）
                if (isDashScope && !isDashScopeCompatible) {
                    String compatUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
                    Map<String, Object> compatBody = new HashMap<>();
                    compatBody.put("model", openaiModel);
                    compatBody.put("messages", messages);
                    compatBody.put("stream", false);                    String compatJson = mapper.writeValueAsString(compatBody);
                    log.info("DashScope 标准端点调用失败，回退 POST {}", compatUrl);
                    res = http.postForEntity(compatUrl, new HttpEntity<>(compatJson, headers), String.class);
                } else {
                    throw callErr;
                }
            }
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
            // 优先支持“自由文本 + NAV_PLAN_JSON: + 纯 JSON”结构
            try {
                String marker = "NAV_PLAN_JSON:";
                int markerIdx = content.indexOf(marker);
                if (markerIdx >= 0) {
                    int startBrace = content.indexOf('{', markerIdx);
                    int endBrace = content.lastIndexOf('}');
                    if (startBrace >= 0 && endBrace > startBrace) {
                        String json = content.substring(startBrace, endBrace + 1);
                        JsonNode planNode = mapper.readTree(json);
                        ItineraryPlan plan = new ItineraryPlan();
                        List<Double> center = new ArrayList<>();
                        for (JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
                        plan.setCityCenter(center.isEmpty() ? List.of(116.402, 39.907) : center);
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
                    }
                }
                // 次优：支持双输出结构 { displayText, navPlan } 或旧版直接 nav JSON
                JsonNode rootNode = mapper.readTree(content);
                JsonNode planNode = rootNode.has("navPlan") ? rootNode.path("navPlan") : rootNode;
                ItineraryPlan plan = new ItineraryPlan();
                List<Double> center = new ArrayList<>();
                for (JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
                plan.setCityCenter(center.isEmpty() ? List.of(116.402, 39.907) : center);
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
            } catch (Exception parseErr) {
                // 纯文本：把内容放入第1天 summary，地图数据留空
                ItineraryPlan plan = new ItineraryPlan();
                plan.setCityCenter(List.of(116.402, 39.907));
                DayPlan day = new DayPlan();
                day.setSummary(content);
                day.setRoutes(Collections.emptyList());
                day.setPois(Collections.emptyList());
                plan.setDays(List.of(day));
                return Optional.of(plan);
            }
        } catch (Exception e) {
            log.warn("调用 LLM 失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 同步调用并同时返回原始文本(rawText)与解析后的结构化计划(plan)。
     */
    public Optional<PlanResult> planWithRaw(String text, String city) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            log.warn("OPENAI API KEY 未配置，跳过 LLM 调用");
            return Optional.empty();
        }
        try {
            String url;
            boolean isDashScope = openaiBaseUrl != null && openaiBaseUrl.contains("dashscope.aliyuncs.com");
            boolean isDashScopeCompatible = isDashScope && (
                    openaiBaseUrl.contains("/compatible") ||
                    openaiBaseUrl.contains("/compatible-mode")
            );
            boolean preferTextGeneration = isDashScope && !isDashScopeCompatible &&
                    (openaiModel != null && openaiModel.toLowerCase(Locale.ROOT).contains("qwen-plus"));
            if (isDashScopeCompatible) {
                if (openaiBaseUrl.endsWith("/v1") || openaiBaseUrl.endsWith("/v1/")) {
                    url = openaiBaseUrl + "/chat/completions";
                } else {
                    url = openaiBaseUrl + "/v1/chat/completions";
                }
            } else if (isDashScope) {
                // DashScope 标准：使用 text-generation/generation
                url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
            } else {
                if (openaiBaseUrl != null && (openaiBaseUrl.endsWith("/v1") || openaiBaseUrl.endsWith("/v1/"))) {
                    url = openaiBaseUrl + "/chat/completions";
                } else {
                    url = openaiBaseUrl + "/v1/chat/completions";
                }
            }

            Map<String, Object> body = new HashMap<>();
            body.put("model", openaiModel);
            String userContentBase = (city == null || city.isBlank()) ? text : (text + "\n城市:" + city);
            String navHint = "\n\n请严格以 JSON 输出，并包含每日导航信息：cityCenter, days[].summary, days[].routes[].polyline/color, days[].pois[].name/coord/type。" +
                    "\n路线 polyline 采用 \"lng,lat;lng,lat;...\" 格式，经纬度为 GCJ-02 或接近值。";
            String userContent = userContentBase + navHint;
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            );

            if (isDashScope && !isDashScopeCompatible) {
                String prompt = systemPrompt + "\n\n用户需求:" + userContent;
                body.put("input", Map.of("prompt", prompt));
                body.put("parameters", Map.of("result_format", "text"));
            } else {
                body.put("messages", messages);
                body.put("stream", false);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);
            String jsonBody = mapper.writeValueAsString(body);
            log.debug("LLM POST {} model={}", url, openaiModel);
            HttpEntity<String> req = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> res;
            try {
                res = http.postForEntity(url, req, String.class);
            } catch (Exception callErr) {
                if (isDashScope && !isDashScopeCompatible) {
                    String compatUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
                    Map<String, Object> compatBody = new HashMap<>();
                    compatBody.put("model", openaiModel);
                    compatBody.put("messages", messages);
                    compatBody.put("stream", false);                    String compatJson = mapper.writeValueAsString(compatBody);
                    log.info("DashScope 标准端点调用失败，回退 POST {}", compatUrl);
                    res = http.postForEntity(compatUrl, new HttpEntity<>(compatJson, headers), String.class);
                } else {
                    throw callErr;
                }
            }
            if (!res.getStatusCode().is2xxSuccessful()) {
                log.warn("OpenAI 非 2xx: {}", res.getStatusCode());
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(res.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) content = root.path("output").path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) content = root.path("output").path("text").asText("");
            if (content.isBlank()) content = root.path("output_text").asText("");
            if (content.isBlank()) {
                log.warn("OpenAI 返回空内容");
                return Optional.empty();
            }

            String rawText = content;
            try {
                // 优先处理“自由文本 + NAV_PLAN_JSON:”格式
                String marker = "NAV_PLAN_JSON:";
                int markerIdx = content.indexOf(marker);
                if (markerIdx >= 0) {
                    String display = content.substring(0, markerIdx).trim();
                    if (display != null && !display.isBlank()) rawText = display;
                    int startBrace = content.indexOf('{', markerIdx);
                    int endBrace = content.lastIndexOf('}');
                    if (startBrace >= 0 && endBrace > startBrace) {
                        String json = content.substring(startBrace, endBrace + 1);
                        JsonNode planNode = mapper.readTree(json);
                        ItineraryPlan plan = new ItineraryPlan();
                        List<Double> center = new ArrayList<>();
                        for (JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
                        plan.setCityCenter(center.isEmpty() ? List.of(116.402, 39.907) : center);
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
                        return Optional.of(new PlanResult(plan, rawText));
                    }
                }
                // 次优：支持 { displayText, navPlan } 或旧版直接 nav JSON
                JsonNode rootNode = mapper.readTree(content);
                String display = rootNode.path("displayText").asText("");
                if (display != null && !display.isBlank()) rawText = display;
                JsonNode planNode = rootNode.has("navPlan") ? rootNode.path("navPlan") : rootNode;
                ItineraryPlan plan = new ItineraryPlan();
                List<Double> center = new ArrayList<>();
                for (JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
                plan.setCityCenter(center.isEmpty() ? List.of(116.402, 39.907) : center);
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
                return Optional.of(new PlanResult(plan, rawText));
            } catch (Exception parseErr) {
                log.info("初次解析失败，尝试提取导航 JSON 回退");
                Optional<ItineraryPlan> extracted = extractNavPlan(rawText, city);
                if (extracted.isPresent()) {
                    return Optional.of(new PlanResult(extracted.get(), rawText));
                }
                ItineraryPlan plan = new ItineraryPlan();
                plan.setCityCenter(java.util.List.of(116.402, 39.907));
                DayPlan day = new DayPlan();
                day.setSummary(content);
                day.setRoutes(java.util.Collections.emptyList());
                day.setPois(java.util.Collections.emptyList());
                plan.setDays(java.util.List.of(day));
                return Optional.of(new PlanResult(plan, rawText));
            }
        } catch (Exception e) {
            log.warn("调用 LLM 失败: {}", e.toString());
            return Optional.empty();
        }
    }

    /**
     * 流式获取纯文本内容（OpenAI 兼容接口）。
     * 仅在非 DashScope 标准端点时启用真实流式；其它情况回退为同步，避免不兼容导致错误。
     */
    
    /**
     * 从原始文本(rawText)中提取结构化导航 JSON，并解析为 ItineraryPlan。
     * 若调用或解析失败，返回 Optional.empty()。
     */
    public Optional<ItineraryPlan> extractNavPlan(String rawText, String city) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            log.warn("OPENAI API KEY 未配置，跳过 LLM 提取调用");
            return Optional.empty();
        }
        try {
            String url;
            boolean isDashScope = openaiBaseUrl != null && openaiBaseUrl.contains("dashscope.aliyuncs.com");
            boolean isDashScopeCompatible = isDashScope && (
                    openaiBaseUrl.contains("/compatible") ||
                    openaiBaseUrl.contains("/compatible-mode")
            );
            if (isDashScopeCompatible) {
                if (openaiBaseUrl.endsWith("/v1") || openaiBaseUrl.endsWith("/v1/")) {
                    url = openaiBaseUrl + "/chat/completions";
                } else {
                    url = openaiBaseUrl + "/v1/chat/completions";
                }
            } else if (isDashScope) {
                // DashScope 标准：使用 text-generation/generation 更稳妥
                url = "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";
            } else {
                if (openaiBaseUrl != null && (openaiBaseUrl.endsWith("/v1") || openaiBaseUrl.endsWith("/v1/"))) {
                    url = openaiBaseUrl + "/chat/completions";
                } else {
                    url = openaiBaseUrl + "/v1/chat/completions";
                }
            }

            String extractSystem =
                    "你是一位资深旅游规划师，请严格以 JSON 输出，不要包含任何解释、反引号或 Markdown。\n" +
                    "目标：仅从下方原始行程文本中提取结构化导航 JSON（cityCenter, days[].summary, days[].routes[].polyline/color, days[].pois[].name/coord/type）。键名必须与规范完全匹配。\n" +
                    "要求：\n" +
                    "- 坚持纯 JSON；不要输出任何多余文本。\n" +
                    "- 每天至少 4–6 个 POI，type 合理标注（如 sight/museum/restaurant/hotel）。\n" +
                    "- 路线 polyline 可选，如生成则为 \"lng,lat;lng,lat;...\"；坐标为 GCJ-02 或接近的经纬度。\n" +
                    "- 若坐标不可确定，可估计常见点位或留空数组。\n" +
                    "- 若用户提供城市或时间、预算偏好，请在 summary 中体现。";

            String userContent =
                    "原始行程文本如下，请按规范提取为 JSON：\n\n" +
                            rawText +
                            ((city == null || city.isBlank()) ? "" : ("\n\n城市:" + city));

            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("model", openaiModel);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            headers.setBearerAuth(openaiApiKey);

            if (isDashScope && !isDashScopeCompatible) {
                String prompt = extractSystem + "\n\n" + userContent;
                body.put("input", java.util.Map.of("prompt", prompt));
                body.put("parameters", java.util.Map.of("result_format", "text"));
            } else {
                java.util.List<java.util.Map<String, String>> messages = java.util.List.of(
                        java.util.Map.of("role", "system", "content", extractSystem),
                        java.util.Map.of("role", "user", "content", userContent)
                );
                body.put("messages", messages);
                body.put("stream", false);
            }

            String jsonBody = mapper.writeValueAsString(body);
            log.debug("LLM(提取) POST {} model={}", url, openaiModel);
            org.springframework.http.HttpEntity<String> req = new org.springframework.http.HttpEntity<>(jsonBody, headers);

            org.springframework.http.ResponseEntity<String> res;
            try {
                res = http.postForEntity(url, req, String.class);
            } catch (Exception callErr) {
                if (isDashScope && !isDashScopeCompatible) {
                    String compatUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
                    java.util.Map<String, Object> compatBody = new java.util.HashMap<>();
                    compatBody.put("model", openaiModel);
                    java.util.List<java.util.Map<String, String>> messages = java.util.List.of(
                            java.util.Map.of("role", "system", "content", extractSystem),
                            java.util.Map.of("role", "user", "content", userContent)
                    );
                    compatBody.put("messages", messages);
                    compatBody.put("stream", false);                    String compatJson = mapper.writeValueAsString(compatBody);
                    log.info("DashScope 标准端点(提取)失败，回退 POST {}", compatUrl);
                    res = http.postForEntity(compatUrl, new org.springframework.http.HttpEntity<>(compatJson, headers), String.class);
                } else {
                    throw callErr;
                }
            }

            if (!res.getStatusCode().is2xxSuccessful()) {
                log.warn("OpenAI(提取) 非 2xx: {}", res.getStatusCode());
                return Optional.empty();
            }

            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(res.getBody());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) content = root.path("output").path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) content = root.path("output").path("text").asText("");
            if (content.isBlank()) content = root.path("output_text").asText("");
            if (content.isBlank()) {
                log.warn("OpenAI(提取) 返回空内容");
                return Optional.empty();
            }

            try {
                com.fasterxml.jackson.databind.JsonNode planNode = mapper.readTree(content);
                ItineraryPlan plan = new ItineraryPlan();
                java.util.List<Double> center = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
                plan.setCityCenter(center.isEmpty() ? java.util.List.of(116.402, 39.907) : center);
                java.util.List<DayPlan> days = new java.util.ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode d : planNode.path("days")) {
                    DayPlan day = new DayPlan();
                    day.setSummary(d.path("summary").asText(null));
                    java.util.List<Route> routes = new java.util.ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode r : d.path("routes")) {
                        Route rt = new Route();
                        rt.setPolyline(r.path("polyline").asText(null));
                        rt.setColor(r.path("color").asText(null));
                        routes.add(rt);
                    }
                    day.setRoutes(routes);
                    java.util.List<Poi> pois = new java.util.ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode p : d.path("pois")) {
                        Poi poi = new Poi();
                        poi.setName(p.path("name").asText(null));
                        java.util.List<Double> coord = new java.util.ArrayList<>();
                        for (com.fasterxml.jackson.databind.JsonNode c : p.path("coord")) { coord.add(c.asDouble()); }
                        poi.setCoord(coord);
                        poi.setType(p.path("type").asText(null));
                        pois.add(poi);
                    }
                    day.setPois(pois);
                    days.add(day);
                }
                plan.setDays(days);
                return Optional.of(plan);
            } catch (Exception parseErr) {
                log.warn("提取回传非 JSON 或解析失败");
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("调用 LLM(提取) 失败: {}", e.toString());
            return Optional.empty();
        }
    }
public void streamText(String text, String city, Consumer<String> onChunk) {
        if (openaiApiKey == null || openaiApiKey.isBlank()) {
            log.warn("OPENAI API KEY 未配置，跳过 LLM 流式调用");
            return;
        }
        try {
            boolean isDashScope = openaiBaseUrl != null && openaiBaseUrl.contains("dashscope.aliyuncs.com");
            boolean isDashScopeCompatible = isDashScope && (
                    openaiBaseUrl.contains("/compatible") || openaiBaseUrl.contains("/compatible-mode")
            );
            // 仅 OpenAI 兼容路径启用 stream=true
            if (isDashScope && !isDashScopeCompatible) {
                // 回退到非流式：直接同步调用并一次性吐出内容
                Optional<ItineraryPlan> planOpt = plan(text, city);
                if (planOpt.isPresent()) {
                    String summary = planOpt.get().getDays() != null && !planOpt.get().getDays().isEmpty()
                            ? planOpt.get().getDays().get(0).getSummary()
                            : "";
                    if (summary != null && !summary.isBlank()) onChunk.accept(summary);
                }
                return;
            }

            String base = (openaiBaseUrl == null || openaiBaseUrl.isBlank()) ? "https://api.openai.com" : openaiBaseUrl;
            String url;
            if (base.endsWith("/v1") || base.endsWith("/v1/")) {
                url = base + "/chat/completions";
            } else {
                url = base + "/v1/chat/completions";
            }

            String userContent = (city == null || city.isBlank()) ? text : (text + "\n城市:" + city);
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userContent)
            );
            Map<String, Object> body = new HashMap<>();
            body.put("model", openaiModel);
            body.put("messages", messages);

            String jsonBody = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + openaiApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonNode node = mapper.readTree(data);
                        // OpenAI 流式：choices[0].delta.content
                        String chunk = node.path("choices").path(0).path("delta").path("content").asText("");
                        if (chunk == null) chunk = "";
                        if (!chunk.isEmpty()) {
                            onChunk.accept(chunk);
                        }
                    } catch (Exception parseErr) {
                        // 非 JSON 行，忽略
                    }
                }
            }
        } catch (Exception e) {
            log.warn("LLM 流式读取失败: {}", e.toString());
        }
    }
}
