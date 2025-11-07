package com.aitravel.planner.controller;

import com.aitravel.planner.itinerary.DayPlan;
import com.aitravel.planner.itinerary.ItineraryPlan;
import com.aitravel.planner.itinerary.Poi;
import com.aitravel.planner.itinerary.Route;
import com.aitravel.planner.service.LlmService;
import com.aitravel.planner.service.AmapService;
import com.aitravel.planner.map.AmapClient;
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
    private final AmapService amap;
    private final AmapClient amapClient;

    public ItineraryController(LlmService llm, AmapService amap, AmapClient amapClient) {
        this.llm = llm;
        this.amap = amap;
        this.amapClient = amapClient;
    }

    public record PlanRequest(String text, String city) {}

    @PostMapping("/plan")
    public ResponseEntity<?> plan(@RequestBody PlanRequest req) {
        if (req == null || req.text() == null || req.text().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text 不能为空"));
        }
        // 先尝试调用 LLM 生成行程（同时返回原始文本与结构化计划）
        Optional<LlmService.PlanResult> resOpt = llm.planWithRaw(req.text(), req.city());
        if (resOpt.isPresent()) {
            LlmService.PlanResult pr = resOpt.get();
            ItineraryPlan enriched = enrichPlan(pr.getPlan(), req.city());
            String rawText = pr.getRawText();
            // 若 rawText 为空，尝试用第1天 summary 兜底
            if (rawText == null || rawText.isBlank()) {
                List<DayPlan> ds = enriched.getDays();
                if (ds != null && !ds.isEmpty() && ds.get(0).getSummary() != null) {
                    rawText = ds.get(0).getSummary();
                }
            }
            return ResponseEntity.ok(Map.of("plan", enriched, "rawText", rawText));
        }
        // 不返回降级示例：若 LLM 不可用或超时，直接返回错误
        return ResponseEntity.status(502).body(Map.of(
                "error", "LLM 不可用或超时，请稍后重试",
                "hint", "请检查 OPENAI_* 配置或提高超时时间"
        ));
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
                            emitter.send(SseEmitter.event().name("draft").data(Map.of(
                                    "plan", draftPlan,
                                    "rawText", acc.toString()
                            )));
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
                    // 解析失败：根据请求文本推断天数与 POI，构造多日计划
                    int daysCount = inferDays(requestText);
                    List<String> poiNames = extractPoiNames(requestText);
                    List<String> fallbackPois = List.of("天安门广场", "王府井", "故宫博物院", "南锣鼓巷", "颐和园", "圆明园");
                    List<String> allNames = new ArrayList<>(poiNames);
                    for (String fp : fallbackPois) { if (allNames.size() < daysCount * 2) allNames.add(fp); }
                    ItineraryPlan plan = new ItineraryPlan();
                    plan.setCityCenter(List.of(116.402, 39.907));
                    List<DayPlan> dayPlans = new ArrayList<>();
                    for (int i = 0; i < Math.max(daysCount, 1); i++) {
                        DayPlan d = new DayPlan();
                        d.setSummary("示例行程 第" + (i + 1) + "天");
                        int a = Math.min(2 * i, allNames.size() - 1);
                        int b = Math.min(2 * i + 1, allNames.size() - 1);
                        Poi pA = new Poi(); pA.setName(allNames.get(a)); pA.setType("sight");
                        Poi pB = new Poi(); pB.setName(allNames.get(b)); pB.setType("sight");
                        d.setPois(List.of(pA, pB));
                        d.setRoutes(List.of());
                        dayPlans.add(d);
                    }
                    plan.setDays(dayPlans);
                    finalPlanOpt = Optional.of(plan);
                }

                emitter.send(SseEmitter.event().name("progress").data(Map.of("stage", "llm_stream_end")));
                ItineraryPlan enriched = enrichPlan(finalPlanOpt.get(), requestCity);
                emitter.send(SseEmitter.event().name("final").data(Map.of(
                        "plan", enriched,
                        "rawText", acc.toString()
                )));
                emitter.complete();
            } catch (Exception e) {
                try { emitter.send(SseEmitter.event().name("error").data(Map.of("message", e.getMessage()))); } catch (Exception ignored) {}
                emitter.completeWithError(e);
            }
        }).start();
        return emitter;
    }

    /**
     * 基于 POI 名称补齐坐标，并为每天生成一条按顺序连接的路线 polyline。
     */
    private ItineraryPlan enrichPlan(ItineraryPlan plan, String city) {
        if (plan == null) return null;
        List<DayPlan> days = plan.getDays();
        if (days == null) return plan;
        final List<Double> cityCenterHint = builtinCityCenter(city);
        List<List<Double>> allCoords = new ArrayList<>();
        for (DayPlan day : days) {
            // 补齐 POI 坐标
            List<Poi> pois = day.getPois();
            List<List<Double>> coords = new ArrayList<>();
            if (pois != null) {
                for (Poi p : pois) {
                    List<Double> c = p.getCoord();
                    // 当明确给出城市时，优先使用城市限定的地理编码覆盖坐标，避免 LLM 错误坐标污染（如北京）
                    if (city != null && !city.isBlank()) {
                        Optional<List<Double>> found = amap.geocodePlace(p.getName(), city);
                        if (found.isPresent()) { c = found.get(); p.setCoord(c); }
                    } else if (c == null || c.size() < 2) {
                        Optional<List<Double>> found = amap.geocodePlace(p.getName(), city);
                        if (found.isPresent()) { c = found.get(); p.setCoord(c); }
                    }
                    // 若仍存在坐标但明显偏离给定城市中心（>80km），视为跨城污染，丢弃
                    if (c != null && c.size() >= 2 && cityCenterHint != null && cityCenterHint.size() == 2) {
                        double distKm = haversineKm(cityCenterHint.get(1), cityCenterHint.get(0), c.get(1), c.get(0));
                        if (distKm > 80) {
                            c = null; p.setCoord(null);
                        }
                    }
                    if (c != null && c.size() >= 2) {
                        coords.add(c);
                        allCoords.add(c);
                    }
                }
            }
            // 若缺少路线，生成一条按 POI 顺序连接的 polyline
            boolean hasPolyline = false;
            if (day.getRoutes() != null) {
                for (Route r : day.getRoutes()) {
                    if (r.getPolyline() != null && !r.getPolyline().isBlank()) { hasPolyline = true; break; }
                }
            }
            // 若已明确城市，忽略 LLM 给出 polyline，改用城市内坐标按顺序生成路线，避免跨城路径
            if (city != null && !city.isBlank()) { hasPolyline = false; }
            if (!hasPolyline && coords.size() >= 2) {
                Route r = new Route();
                r.setColor("#3b82f6");
                r.setPolyline(amap.buildPolylineBySequence(coords));
                List<Route> rs = new ArrayList<>();
                rs.add(r);
                day.setRoutes(rs);
            } else if (!hasPolyline) {
                // 没有坐标可用时，清空可能由 LLM 生成的跨城路线
                day.setRoutes(java.util.List.of());
            }
        }
        // 若城市中心缺失，使用所有坐标的平均值；当坐标也缺失且给出了城市名时，使用内置城市中心兜底
        if (plan.getCityCenter() == null || plan.getCityCenter().isEmpty()) {
            if (!allCoords.isEmpty()) {
                double avgLng = allCoords.stream().mapToDouble(c -> c.get(0)).average().orElse(116.402);
                double avgLat = allCoords.stream().mapToDouble(c -> c.get(1)).average().orElse(39.907);
                plan.setCityCenter(List.of(avgLng, avgLat));
            } else {
                List<Double> builtin = cityCenterHint;
                plan.setCityCenter(builtin != null ? builtin : List.of(116.402, 39.907));
            }
        }
        return plan;
    }

    /**
     * 内置城市中心兜底：当地理编码不可用或坐标缺失且提供了城市名时，避免默认回落到北京。
     */
    private List<Double> builtinCityCenter(String city) {
        if (city == null || city.isBlank()) return null;
        String key = city.trim();
        java.util.Map<String, java.util.List<Double>> centers = java.util.Map.ofEntries(
                java.util.Map.entry("北京", java.util.List.of(116.402, 39.907)),
                java.util.Map.entry("上海", java.util.List.of(121.4737, 31.2304)),
                java.util.Map.entry("广州", java.util.List.of(113.2644, 23.1291)),
                java.util.Map.entry("深圳", java.util.List.of(114.0579, 22.5431)),
                java.util.Map.entry("杭州", java.util.List.of(120.1551, 30.2741)),
                java.util.Map.entry("南京", java.util.List.of(118.7969, 32.0603)),
                java.util.Map.entry("苏州", java.util.List.of(120.5853, 31.2989)),
                java.util.Map.entry("成都", java.util.List.of(104.0665, 30.5728)),
                java.util.Map.entry("重庆", java.util.List.of(106.5516, 29.5630)),
                java.util.Map.entry("西安", java.util.List.of(108.9398, 34.3416)),
                java.util.Map.entry("武汉", java.util.List.of(114.3055, 30.5928)),
                java.util.Map.entry("厦门", java.util.List.of(118.0894, 24.4798)),
                java.util.Map.entry("青岛", java.util.List.of(120.3826, 36.0671))
        );
        return centers.getOrDefault(key, null);
    }

    // 计算两点之间的球面距离（公里），用于识别跨城坐标污染
    private double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371.0; // km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng/2) * Math.sin(dLng/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        return R * c;
    }

    /**
     * 获取指定天的导航路线：
     * - 优先使用大模型返回的 routes.polyline
     * - 若缺失，则按 POI 顺序调用高德驾车路线 API 生成 polyline 段集合
     * 参数：text/city/day（day 从 1 开始计数）
     */
    @GetMapping("/day-route")
    public ResponseEntity<?> dayRoute(@RequestParam("text") String text,
                                      @RequestParam(value = "city", required = false) String city,
                                      @RequestParam("day") Integer dayIndex) throws Exception {
        if (dayIndex == null || dayIndex < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "day 必须为从 1 开始的正整数"));
        }
        Optional<ItineraryPlan> planOpt = llm.plan(text, city);
        ItineraryPlan plan;
        if (planOpt.isPresent() && planOpt.get().getDays() != null && !planOpt.get().getDays().isEmpty()) {
            plan = planOpt.get();
        } else {
            // 降级：基于文本推断天数和 POI，复用现有降级逻辑
            int daysCount = inferDays(text);
            List<String> poiNames = extractPoiNames(text);
            List<String> fallbackPois = List.of("天安门广场", "王府井", "故宫博物院", "南锣鼓巷", "颐和园", "圆明园");
            List<String> allNames = new ArrayList<>(poiNames);
            for (String fp : fallbackPois) { if (allNames.size() < daysCount * 2) allNames.add(fp); }
            List<DayPlan> days = new ArrayList<>();
            for (int i = 0; i < Math.max(daysCount, 1); i++) {
                DayPlan d = new DayPlan();
                d.setSummary("示例行程 第" + (i + 1) + "天");
                int a = Math.min(2 * i, allNames.size() - 1);
                int b = Math.min(2 * i + 1, allNames.size() - 1);
                Poi pA = new Poi(); pA.setName(allNames.get(a)); pA.setType("sight");
                Poi pB = new Poi(); pB.setName(allNames.get(b)); pB.setType("sight");
                d.setPois(List.of(pA, pB));
                d.setRoutes(List.of());
                days.add(d);
            }
            plan = new ItineraryPlan();
            plan.setCityCenter(List.of(116.402, 39.907));
            plan.setDays(days);
        }

        // 补齐坐标与简单路线（便于后续使用 POI 坐标）
        plan = enrichPlan(plan, city);
        List<DayPlan> days = plan.getDays();
        if (days == null || dayIndex > days.size()) {
            return ResponseEntity.badRequest().body(Map.of("error", "day 超过行程天数范围"));
        }
        DayPlan target = days.get(dayIndex - 1);

        // 若 LLM 已给出 routes，直接返回
        List<Route> given = target.getRoutes();
        if (given != null && !given.isEmpty() && given.stream().anyMatch(r -> r.getPolyline() != null && !r.getPolyline().isBlank())) {
            return ResponseEntity.ok(Map.of("day", dayIndex, "routes", given));
        }

        // 否则用驾车路线补齐：对相邻 POI 计算 polyline
        List<Poi> pois = target.getPois();
        if (pois == null || pois.size() < 2) {
            return ResponseEntity.ok(Map.of("day", dayIndex, "routes", List.of()));
        }
        List<Map<String, Object>> routesOut = new ArrayList<>();
        long totalDistance = 0;
        long totalDuration = 0;
        for (int i = 0; i < pois.size() - 1; i++) {
            List<Double> c1 = pois.get(i).getCoord();
            List<Double> c2 = pois.get(i + 1).getCoord();
            if (c1 == null || c1.size() < 2) {
                Optional<List<Double>> found = amap.geocodePlace(pois.get(i).getName(), city);
                if (found.isPresent()) { c1 = found.get(); pois.get(i).setCoord(c1); }
            }
            if (c2 == null || c2.size() < 2) {
                Optional<List<Double>> found = amap.geocodePlace(pois.get(i + 1).getName(), city);
                if (found.isPresent()) { c2 = found.get(); pois.get(i + 1).setCoord(c2); }
            }
            if (c1 == null || c2 == null || c1.size() < 2 || c2.size() < 2) continue;
            String origin = c1.get(0) + "," + c1.get(1);
            String destination = c2.get(0) + "," + c2.get(1);
            try {
                var r = amapClient.driving(origin, destination);
                String polyline = r.polyline() == null ? amap.buildPolylineBySequence(List.of(c1, c2)) : r.polyline();
                routesOut.add(Map.of("polyline", polyline, "color", "#3b82f6"));
                totalDistance += r.distance();
                totalDuration += r.duration();
            } catch (Exception e) {
                // 回退为直线连接
                routesOut.add(Map.of("polyline", amap.buildPolylineBySequence(List.of(c1, c2)), "color", "#3b82f6"));
            }
        }
        return ResponseEntity.ok(Map.of(
                "day", dayIndex,
                "routes", routesOut,
                "total", Map.of("distance", totalDistance, "duration", totalDuration)
        ));
    }

    // 直接按前端已生成的当天 POI 计算路线，避免重复调用 LLM 导致 POI 缺失
    public record PoiReq(String name, java.util.List<Double> coord) {}
    public record DayRouteComputeReq(java.util.List<PoiReq> pois, String city) {}

    @PostMapping("/day-route/compute")
    public ResponseEntity<?> dayRouteCompute(@RequestBody DayRouteComputeReq req) throws Exception {
        if (req == null || req.pois() == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "请求体缺少 pois"));
        }
        if (req.pois().size() < 2) {
            // 与 GET /day-route 行为保持一致：当少于2个POI时返回空路线而非错误
            return ResponseEntity.ok(java.util.Map.of(
                    "routes", java.util.List.of(),
                    "total", java.util.Map.of("distance", 0, "duration", 0)
            ));
        }
        java.util.List<PoiReq> pois = req.pois();
        String city = req.city();
        java.util.List<java.util.Map<String, Object>> routesOut = new java.util.ArrayList<>();
        long totalDistance = 0;
        long totalDuration = 0;
        for (int i = 0; i < pois.size() - 1; i++) {
            PoiReq p1 = pois.get(i);
            PoiReq p2 = pois.get(i + 1);
            java.util.List<Double> c1 = p1.coord();
            java.util.List<Double> c2 = p2.coord();
            if (c1 == null || c1.size() < 2) {
                java.util.Optional<java.util.List<Double>> found = amap.geocodePlace(p1.name(), city);
                if (found.isPresent()) c1 = found.get();
            }
            if (c2 == null || c2.size() < 2) {
                java.util.Optional<java.util.List<Double>> found = amap.geocodePlace(p2.name(), city);
                if (found.isPresent()) c2 = found.get();
            }
            if (c1 == null || c2 == null || c1.size() < 2 || c2.size() < 2) continue;
            String origin = c1.get(0) + "," + c1.get(1);
            String destination = c2.get(0) + "," + c2.get(1);
            try {
                var r = amapClient.driving(origin, destination);
                String polyline = r.polyline() == null ? amap.buildPolylineBySequence(java.util.List.of(c1, c2)) : r.polyline();
                routesOut.add(java.util.Map.of("polyline", polyline, "color", "#3b82f6"));
                totalDistance += r.distance();
                totalDuration += r.duration();
            } catch (Exception e) {
                routesOut.add(java.util.Map.of("polyline", amap.buildPolylineBySequence(java.util.List.of(c1, c2)), "color", "#3b82f6"));
            }
        }
        return ResponseEntity.ok(java.util.Map.of(
                "routes", routesOut,
                "total", java.util.Map.of("distance", totalDistance, "duration", totalDuration)
        ));
    }

    // 简单从中文文本中推断天数：支持 “2天”“两天”“三天”等形式
    private int inferDays(String text) {
        if (text == null) return 1;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*天").matcher(text);
            if (m.find()) {
                int n = Integer.parseInt(m.group(1));
                return Math.max(1, Math.min(n, 10));
            }
        } catch (Exception ignored) {}
        Map<String, Integer> map = Map.ofEntries(
                Map.entry("一", 1),
                Map.entry("二", 2),
                Map.entry("两", 2),
                Map.entry("三", 3),
                Map.entry("四", 4),
                Map.entry("五", 5),
                Map.entry("六", 6),
                Map.entry("七", 7),
                Map.entry("八", 8),
                Map.entry("九", 9),
                Map.entry("十", 10)
        );
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (text.contains(e.getKey() + "天")) return e.getValue();
        }
        return 1;
    }

    // 从文本里粗略提取 POI 名称（按中文顿号/逗号等分割），用于降级示例
    private List<String> extractPoiNames(String text) {
        if (text == null) return List.of();
        String[] parts = text.split("[、，,；;\\s]+");
        List<String> names = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (s.length() >= 2 && !s.matches(".*(两天|三天|天|城市|探索|预算|路线|酒店|餐厅).*")) {
                names.add(s);
            }
        }
        // 去重
        LinkedHashSet<String> set = new LinkedHashSet<>(names);
        return new ArrayList<>(set);
    }
}
