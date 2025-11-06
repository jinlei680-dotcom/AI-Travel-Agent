package com.aitravel.planner.controller;

import com.aitravel.planner.itinerary.DayPlan;
import com.aitravel.planner.itinerary.ItineraryPlan;
import com.aitravel.planner.itinerary.Poi;
import com.aitravel.planner.itinerary.Route;
import com.aitravel.planner.service.LlmService;
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
    public SseEmitter stream(@RequestParam("text") String text,
                             @RequestParam(value = "city", required = false) String city) {
        final SseEmitter emitter = new SseEmitter(0L); // 不限时，前端关闭连接即可
        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "init")));
                Optional<ItineraryPlan> planOpt = llm.plan(text, city);
                if (planOpt.isPresent()) {
                    emitter.send(SseEmitter.event().name("final").data(Map.of("plan", planOpt.get())));
                    emitter.complete();
                    return;
                }
                // 降级：模拟草稿与最终结果分步发送（北京 天安门→王府井），便于前端体验流式
                String polyline = "116.397477,39.908692;116.405285,39.904989";
                Route r1 = new Route(); r1.setPolyline(polyline); r1.setColor("#ef4444");
                Poi p1 = new Poi(); p1.setName("天安门广场"); p1.setCoord(List.of(116.397477, 39.908692)); p1.setType("sight");
                Poi p2 = new Poi(); p2.setName("王府井"); p2.setCoord(List.of(116.405285, 39.904989)); p2.setType("shopping");

                DayPlan draftDay = new DayPlan(); draftDay.setSummary("生成草稿：第1天主要景点");
                draftDay.setRoutes(List.of(r1)); draftDay.setPois(List.of(p1));
                ItineraryPlan draftPlan = new ItineraryPlan(); draftPlan.setCityCenter(List.of(116.402, 39.907)); draftPlan.setDays(List.of(draftDay));
                emitter.send(SseEmitter.event().name("draft").data(Map.of("plan", draftPlan)));

                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "llm_draft")));
                Thread.sleep(600);
                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "route_compute")));
                Thread.sleep(600);

                DayPlan finalDay = new DayPlan(); finalDay.setSummary("历史与步行街探索"); finalDay.setRoutes(List.of(r1)); finalDay.setPois(List.of(p1, p2));
                ItineraryPlan finalPlan = new ItineraryPlan(); finalPlan.setCityCenter(List.of(116.402, 39.907)); finalPlan.setDays(List.of(finalDay));
                emitter.send(SseEmitter.event().name("final").data(Map.of("plan", finalPlan)));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage()))); } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }
}