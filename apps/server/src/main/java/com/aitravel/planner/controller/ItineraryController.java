package com.aitravel.planner.controller;

import com.aitravel.planner.itinerary.DayPlan;
import com.aitravel.planner.itinerary.ItineraryPlan;
import com.aitravel.planner.itinerary.Poi;
import com.aitravel.planner.itinerary.Route;
import com.aitravel.planner.service.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/itinerary")
@CrossOrigin(origins = "*", allowCredentials = "false")
public class ItineraryController {

    private final LlmService llm;

    public ItineraryController(LlmService llm) { this.llm = llm; }

    public record PlanRequest(String text, String city) {}

    @PostMapping("/plan")
    public ResponseEntity<?> plan(@RequestBody PlanRequest req) {
        if (req == null || req.text() == null || req.text().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        // 先尝试调用 LLM 生成行程
        Optional<ItineraryPlan> planOpt = llm.plan(req.text(), req.city());
        if (planOpt.isPresent()) {
            return ResponseEntity.ok(Map.of("plan", planOpt.get()));
        }
        // 降级：返回示例数据（天安门→王府井）
        String polyline = "116.397477,39.908692;116.405285,39.904989";
        Route r1 = new Route(); r1.setPolyline(polyline); r1.setColor("#ef4444");
        Poi p1 = new Poi(); p1.setName("天安门广场"); p1.setCoord(List.of(116.397477, 39.908692)); p1.setType("sight");
        Poi p2 = new Poi(); p2.setName("王府井"); p2.setCoord(List.of(116.405285, 39.904989)); p2.setType("shopping");
        DayPlan d1 = new DayPlan(); d1.setSummary("历史与步行街探索"); d1.setRoutes(List.of(r1)); d1.setPois(List.of(p1, p2));
        ItineraryPlan plan = new ItineraryPlan(); plan.setCityCenter(List.of(116.402, 39.907)); plan.setDays(List.of(d1));
        return ResponseEntity.ok(Map.of("plan", plan));
    }

    /**
     * 流式行程规划（SSE）：返回 progress/draft/final 事件，便于前端实时渲染与地图联动。
     * GET 以便兼容 EventSource；输入使用 query 参数 text/city。
     */
    @GetMapping(path = "/plan/stream")
    public SseEmitter stream(@RequestParam(value = "text", required = false) String text,
                             @RequestParam(value = "city", required = false) String city,
                             @RequestParam(value = "destination", required = false) String destination,
                             @RequestParam(value = "days", required = false) Integer days) {
        final SseEmitter emitter = new SseEmitter(0L); // 不限时，前端关闭连接即可
        // 参数兼容：若未提供 text，则基于 destination/days 构造提示（在线程外，避免 lambda 捕获非最终变量）
        String effectiveText = text;
        if (effectiveText == null || effectiveText.trim().isEmpty()) {
            StringBuilder prompt = new StringBuilder();
            String cityName = (city != null && !city.isBlank()) ? city : destination;
            if (cityName != null && !cityName.isBlank()) {
                prompt.append("为城市").append(cityName);
            } else {
                prompt.append("为指定目的地");
            }
            if (days != null && days > 0) {
                prompt.append("规划").append(days).append("天行程。");
            } else {
                prompt.append("规划合理行程。");
            }
            prompt.append("请以 JSON 输出包含 cityCenter、days（每天 summary、routes、pois）。");
            effectiveText = prompt.toString();
        }
        final String requestText = effectiveText;
        final String requestCity = city;
        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "init")));
                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "llm_stream_start")));
                final StringBuilder acc = new StringBuilder();
                final boolean[] drafted = new boolean[]{false};
                // 真实流式：按增量文本发送，一定长度后发送一次草稿，让前端先渲染
                llm.streamText(requestText, requestCity, chunk -> {
                    try {
                        acc.append(chunk);
                        if (!drafted[0] && acc.length() > 200) {
                            DayPlan draftDay = new DayPlan();
                            draftDay.setSummary(acc.toString());
                            draftDay.setRoutes(List.of());
                            draftDay.setPois(List.of());
                            ItineraryPlan draftPlan = new ItineraryPlan();
                            draftPlan.setCityCenter(List.of(116.402, 39.907));
                            draftPlan.setDays(List.of(draftDay));
                            emitter.send(SseEmitter.event().name("draft").data(Map.of("plan", draftPlan)));
                            drafted[0] = true;
                        }
                    } catch (Exception ignored) {}
                });

                // 流式结束，尝试解析完整 JSON，否则以纯文本汇总
                Optional<ItineraryPlan> finalPlanOpt;
                try {
                    JsonNode planNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(acc.toString());
                    ItineraryPlan plan = new ItineraryPlan();
                    List<Double> center = new ArrayList<>();
                    for (JsonNode n : planNode.path("cityCenter")) { center.add(n.asDouble()); }
                    plan.setCityCenter(center.isEmpty() ? List.of(116.402, 39.907) : center);
                    List<Route> routes = new ArrayList<>();
                    List<DayPlan> dayPlans = new ArrayList<>();
                    for (JsonNode d : planNode.path("days")) {
                        DayPlan day = new DayPlan();
                        day.setSummary(d.path("summary").asText(null));
                        List<Route> dayRoutes = new ArrayList<>();
                        for (JsonNode r : d.path("routes")) {
                            Route rt = new Route();
                            rt.setPolyline(r.path("polyline").asText(null));
                            rt.setColor(r.path("color").asText(null));
                            dayRoutes.add(rt);
                        }
                        day.setRoutes(dayRoutes);
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
                        dayPlans.add(day);
                    }
                    plan.setDays(dayPlans);
                    finalPlanOpt = Optional.of(plan);
                } catch (Exception parseErr) {
                    ItineraryPlan plan = new ItineraryPlan();
                    plan.setCityCenter(List.of(116.402, 39.907));
                    DayPlan day = new DayPlan();
                    day.setSummary(acc.toString());
                    day.setRoutes(List.of());
                    day.setPois(List.of());
                    plan.setDays(List.of(day));
                    finalPlanOpt = Optional.of(plan);
                }

                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "llm_stream_end")));
                emitter.send(SseEmitter.event().name("final").data(Map.of("plan", finalPlanOpt.get())));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage()))); } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }
}