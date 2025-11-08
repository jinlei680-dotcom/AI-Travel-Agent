package com.aitravel.planner.controller;

import com.aitravel.planner.itinerary.DayPlan;
import com.aitravel.planner.itinerary.ItineraryPlan;
import com.aitravel.planner.itinerary.Poi;
import com.aitravel.planner.itinerary.Route;
import com.aitravel.planner.service.LlmService;
import com.aitravel.planner.service.AmapService;
import com.aitravel.planner.map.AmapClient;
import com.aitravel.planner.itinerary.Budget;
import com.aitravel.planner.itinerary.BudgetBreakdown;
import com.aitravel.planner.itinerary.BudgetCategory;
import com.aitravel.planner.itinerary.BudgetItem;
import com.aitravel.planner.util.BudgetParser;
import com.aitravel.planner.util.BudgetVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            String rawText = pr.getRawText();
            try {
                rawText = com.aitravel.planner.util.BudgetRawTextNormalizer.normalize(rawText);
            } catch (Exception ignored) {}
            // 从原文中尝试提取“总预算”并同步到结构化计划（覆盖/填充 baseBudget）
            Budget tb = extractTotalBudgetFromText(rawText);
            // 兜底：若原文未提取到总预算，则总是尝试从请求文本中提取（请求里通常包含“总预算<数值> <币种>”）
            if ((tb == null || tb.getAmount() == null || tb.getAmount().compareTo(BigDecimal.ZERO) <= 0)) {
                try {
                    Budget fromReq = extractTotalBudgetFromText(req.text());
                    if (fromReq != null) tb = fromReq;
                } catch (Exception ignored) {}
            }
            if (tb != null) {
                try { pr.getPlan().setBaseBudget(tb); } catch (Exception ignored) {}
            }
            ItineraryPlan enriched = enrichPlan(pr.getPlan(), req.city());
            // 优先：使用 LLM 返回的 typed POIs 生成 daily（restaurant/hotel/sight/transport）
            List<Map<String, Object>> daily = convertPlanToDaily(enriched);
            // 兜底：若 LLM 未提供类型或为空，再回退到原文解析
            if (daily == null || daily.isEmpty()) {
                daily = parseDailyFromRawText(rawText, req.city());
            }
            // 解析结构化预算并返回一致性标志
            BudgetBreakdown breakdown = BudgetParser.parse(rawText);
            breakdown = BudgetVerifier.fixAndAlign(breakdown);
            // 当原文预算缺失或解析为 0 时，依据 baseBudget 提供保守的降级拆分（标记为未对齐）
            try {
                if (breakdown == null) breakdown = new BudgetBreakdown();
                BigDecimal grand = breakdown.getGrandTotal() == null ? BigDecimal.ZERO : breakdown.getGrandTotal();
                // 条件1：总计为 0（无预算解析结果）
                if (grand.compareTo(BigDecimal.ZERO) == 0) {
                    Budget base = enriched != null ? enriched.getBaseBudget() : null;
                    if (base != null && base.getAmount() != null && base.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal total = base.getAmount();
                        String cur = base.getCurrency() == null || base.getCurrency().isBlank() ? "CNY" : base.getCurrency();
                        java.util.List<BudgetCategory> cats = new java.util.ArrayList<>();
                        java.util.function.BiFunction<String, BigDecimal, BudgetCategory> mk = (name, amt) -> {
                            BudgetCategory c = new BudgetCategory(name, cur);
                            // 单项“估算”以便前端显示具体费用项
                            BudgetItem it = new BudgetItem(name + "（估算）", amt, cur);
                            c.getItems().add(it);
                            c.setTotal(amt);
                            return c;
                        };
                        cats.add(mk.apply("住宿", total.multiply(new BigDecimal("0.40"))));
                        cats.add(mk.apply("餐饮", total.multiply(new BigDecimal("0.30"))));
                        cats.add(mk.apply("交通", total.multiply(new BigDecimal("0.15"))));
                        cats.add(mk.apply("门票", total.multiply(new BigDecimal("0.15"))));
                        breakdown.setCategories(cats);
                        breakdown.setCurrency(cur);
                        breakdown.setGrandTotal(total);
                        breakdown.setAligned(false);
                    }
                }
                // 条件2：类别有效值过少（例如仅一个类别非零），也进行降级拆分以给出可用的具体项
                int nonZeroCats = 0;
                for (BudgetCategory c : breakdown.getCategories()) {
                    if (c.getTotal() != null && c.getTotal().compareTo(BigDecimal.ZERO) > 0) nonZeroCats++;
                }
                if (nonZeroCats < 2) {
                    Budget base = enriched != null ? enriched.getBaseBudget() : null;
                    if (base != null && base.getAmount() != null && base.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal total = base.getAmount();
                        String cur = base.getCurrency() == null || base.getCurrency().isBlank() ? "CNY" : base.getCurrency();
                        java.util.List<BudgetCategory> cats = new java.util.ArrayList<>();
                        java.util.function.BiFunction<String, BigDecimal, BudgetCategory> mk = (name, amt) -> {
                            BudgetCategory c = new BudgetCategory(name, cur);
                            BudgetItem it = new BudgetItem(name + "（估算）", amt, cur);
                            c.getItems().add(it);
                            c.setTotal(amt);
                            return c;
                        };
                        cats.add(mk.apply("住宿", total.multiply(new BigDecimal("0.40"))));
                        cats.add(mk.apply("餐饮", total.multiply(new BigDecimal("0.30"))));
                        cats.add(mk.apply("交通", total.multiply(new BigDecimal("0.15"))));
                        cats.add(mk.apply("门票", total.multiply(new BigDecimal("0.15"))));
                        breakdown.setCategories(cats);
                        breakdown.setCurrency(cur);
                        breakdown.setGrandTotal(total);
                        breakdown.setAligned(false);
                    }
                }
            } catch (Exception ignored) {}
            // 按要求：不再以摘要兜底，保持原文（可能为空）
            return ResponseEntity.ok(Map.of(
                    "plan", enriched,
                    "rawText", rawText,
                    "daily", daily,
                    "budget", breakdown,
                    "budgetAligned", breakdown != null && breakdown.isAligned()
            ));
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
            prompt.append("请以 JSON 输出包含 cityCenter、baseBudget{amount,currency}、days（每天 summary、routes、pois）。");
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
                    JsonNode budgetNode = planNode.path("baseBudget");
                    if (budgetNode.isObject()) {
                        try {
                            java.math.BigDecimal amt = new java.math.BigDecimal(budgetNode.path("amount").asText("0"));
                            String cur = budgetNode.path("currency").asText(null);
                            if (cur != null && !cur.isBlank()) plan.setBaseBudget(new com.aitravel.planner.itinerary.Budget(amt, cur));
                        } catch (Exception ignored) {}
                    }
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
                // 当 LLM 流输出为空时，回退使用请求文本进行预算解析，确保无 API Key 也能得到预算结果
                String budgetSource = acc.length() > 0 ? acc.toString() : requestText;
                BudgetBreakdown breakdown = BudgetParser.parse(budgetSource);
                breakdown = BudgetVerifier.fixAndAlign(breakdown);
                emitter.send(SseEmitter.event().name("final").data(Map.of(
                        "plan", enriched,
                        "rawText", acc.toString(),
                        "budget", breakdown,
                        "budgetAligned", breakdown != null && breakdown.isAligned()
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
     * 从原始文本中提取“总预算”金额与币种，映射为 Budget。
     * 支持示例："总预算：5000元人民币"、"总预算: 5000 CNY"、"预算控制在5000人民币左右"。
     */
    private Budget extractTotalBudgetFromText(String raw) {
        if (raw == null) return null;
        String text = raw.replace(",", "");
        // 1) 优先匹配以“总预算”开头的表达
        Pattern p1 = Pattern.compile("总预算[：:]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(人民币|RMB|CNY|元)?", Pattern.CASE_INSENSITIVE);
        Matcher m1 = p1.matcher(text);
        if (m1.find()) {
            try {
                BigDecimal amt = new BigDecimal(m1.group(1));
                String cur = normalizeCurrency(m1.group(2), text);
                return new Budget(amt, cur);
            } catch (Exception ignored) {}
        }
        // 2) 次优：一般性预算表达（含金额与币种）
        Pattern p2 = Pattern.compile("预算(?:控制在|约|大约|大概)?[^\\n]*?([0-9]+(?:\\.[0-9]+)?)\\s*(人民币|RMB|CNY|元)", Pattern.CASE_INSENSITIVE);
        Matcher m2 = p2.matcher(text);
        if (m2.find()) {
            try {
                BigDecimal amt = new BigDecimal(m2.group(1));
                String cur = normalizeCurrency(m2.group(2), text);
                return new Budget(amt, cur);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String normalizeCurrency(String curRaw, String fullText) {
        String c = curRaw == null ? "" : curRaw.trim().toUpperCase();
        if (c.isEmpty()) {
            // 根据上下文推断：含“人民币/元/RMB/CNY”默认 CNY，否则保守默认 CNY
            String t = fullText == null ? "" : fullText.toUpperCase();
            if (t.contains("人民币") || t.contains("元") || t.contains("RMB") || t.contains("CNY")) return "CNY";
            return "CNY";
        }
        if (c.equals("人民币") || c.equals("元") || c.equals("RMB") || c.equals("CNY")) return "CNY";
        return c;
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
        // 若基础预算缺失，按天数提供默认预算（简化：每人每天 500 CNY）
        if (plan.getBaseBudget() == null) {
            int dcount = days.size();
            java.math.BigDecimal amt = java.math.BigDecimal.valueOf(Math.max(1, dcount) * 500L);
            plan.setBaseBudget(new com.aitravel.planner.itinerary.Budget(amt, "CNY"));
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

    /**
     * 从原文(rawText)解析每日“景点/住宿/餐饮/交通”信息。
     * 设计目标：
     * - 优先按“第X天/Day X/D1”等标题切分；
     * - 每段内按关键字分类（酒店/住宿、餐厅/早餐/午餐/晚餐、地铁/交通）；其余归为景点；
     * - 若未检测到任何天标题，回退使用 LLM 提取 nav plan 并据 POI 名称进行分类。
     */
    private List<Map<String, Object>> parseDailyFromRawText(String rawText, String city) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (rawText == null || rawText.trim().isEmpty()) return out;
        String raw = rawText;
        // 收集“第X天”中文标题
        java.util.regex.Pattern pZh = java.util.regex.Pattern.compile("(\\n|^)[#\\s]*第\\s*([0-9一二三四五六七八九十]+)\\s*天[^\\n]*", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern pEn = java.util.regex.Pattern.compile("(\\n|^)[#\\s]*(?:DAY|Day|D)\\s*([0-9]+)[^\\n]*", java.util.regex.Pattern.CASE_INSENSITIVE);
        List<int[]> ranges = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        java.util.regex.Matcher mZh = pZh.matcher(raw);
        while (mZh.find()) {
            int start = mZh.start();
            titles.add("第 " + mZh.group(2) + " 天");
            ranges.add(new int[]{start, -1});
        }
        // 仅当未找到中文标题时再尝试英文标题
        if (ranges.isEmpty()) {
            java.util.regex.Matcher mEn = pEn.matcher(raw);
            while (mEn.find()) {
                int start = mEn.start();
                titles.add("Day " + mEn.group(2));
                ranges.add(new int[]{start, -1});
            }
        }
        // 完成每个区间的结束位置
        for (int i = 0; i < ranges.size(); i++) {
            ranges.get(i)[1] = (i + 1 < ranges.size()) ? ranges.get(i + 1)[0] : raw.length();
        }
        // 分类关键字（增强餐饮/交通识别，并过滤预算等非 POI 文本）
        java.util.function.Function<String, String> classify = (name) -> {
            String n = name == null ? "" : name.trim();
            // 基础清洗：去掉日标题、末尾标点与“等”等尾词
            n = n.replaceAll("^(第\\s*[0-9一二三四五六七八九十]+\\s*天|(?:DAY|Day|D)\\s*\\d+)[：:]?", "");
            n = n.replaceAll("[。.!？！…]+$", "");
            n = n.replaceAll("(等|之类)$", "");

            // 非 POI：预算/费用描述直接跳过
            if (n.matches(".*(预算|费用|花费|人均|价格|约\\s*\\d+|¥|元).*")) return "nonpoi";

            // 住宿：更全面的关键词
            if (n.matches(".*(酒店|民宿|宾馆|旅店|客栈|青旅|入住|住宿|inn|hostel|hotel).*")) return "lodging";

            // 餐饮：场所与常见菜品/食物关键词（覆盖小笼包/粉丝汤等）
            if (n.matches(".*(餐厅|餐馆|饭店|酒楼|菜馆|小吃|美食|早餐|午餐|晚餐|早茶|夜宵|奶茶|茶馆|咖啡|咖啡馆|烘焙|甜品|糕点|面馆|粉馆|烧烤|火锅|烤鸭|米线|螺蛳粉|小笼包|包子|馄饨|粉丝汤|拉面|牛肉面|汤包|生煎|串串|蟹黄汤包|砂锅|汤|面|粉).*")) return "restaurant";

            // 交通：到达/出发/站点等
            if (n.matches(".*(地铁|公交|火车|高铁|航班|机场|车站|码头|出租车|打车|步行|骑行|交通|抵达|到达|出发|前往|转乘|换乘).*")) return "transport";

            return "attraction";
        };

        // 解析每段
        for (int i = 0; i < ranges.size(); i++) {
            int s = ranges.get(i)[0];
            int e = ranges.get(i)[1];
            String chunk = raw.substring(s, Math.max(s, e));
            String title = titles.get(i);
            List<String> atts = new ArrayList<>();
            List<String> lods = new ArrayList<>();
            List<String> rests = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            // 按行扫描：从项目符号或逗号/顿号分割提取候选名称
            String[] lines = chunk.split("\\n+");
            for (String line : lines) {
                String cleaned = line.replaceAll("^[#>*\\s-•·]+", "").trim();
                if (cleaned.length() < 2) continue;
                String[] parts = cleaned.split("[、，,；;\\s]+");
                for (String part : parts) {
                    String nm = part.trim();
                    if (nm.length() < 2) continue;
                    String t = classify.apply(nm);
                    if (t.equals("nonpoi")) continue; // 跳过预算/费用等非 POI
                    if (t.equals("lodging")) lods.add(nm);
                    else if (t.equals("restaurant")) rests.add(nm);
                    else if (t.equals("transport")) trans.add(nm);
                    else atts.add(nm);
                }
            }
            // 去重、精简
            lods = new ArrayList<>(new java.util.LinkedHashSet<>(lods));
            rests = new ArrayList<>(new java.util.LinkedHashSet<>(rests));
            trans = new ArrayList<>(new java.util.LinkedHashSet<>(trans));
            atts = new ArrayList<>(new java.util.LinkedHashSet<>(atts));
            java.util.Map<String, Object> day = new java.util.LinkedHashMap<>();
            day.put("title", title);
            day.put("attractions", atts);
            day.put("lodging", lods);
            day.put("restaurants", rests);
            day.put("transport", trans);
            out.add(day);
        }

        // 若未解析到任何天标题，尝试调用 LLM 从原文提取 nav plan 并据 POI 分类
        if (out.isEmpty()) {
            try {
                java.util.Optional<com.aitravel.planner.itinerary.ItineraryPlan> planOpt = llm.extractNavPlan(rawText, city);
                if (planOpt.isPresent() && planOpt.get().getDays() != null) {
                    List<com.aitravel.planner.itinerary.DayPlan> ds = planOpt.get().getDays();
                    for (int i = 0; i < ds.size(); i++) {
                        com.aitravel.planner.itinerary.DayPlan d = ds.get(i);
                        List<String> atts = new ArrayList<>();
                        List<String> lods = new ArrayList<>();
                        List<String> rests = new ArrayList<>();
                        List<String> trans = new ArrayList<>();
                        List<com.aitravel.planner.itinerary.Poi> pois = d.getPois();
                        if (pois != null) {
                            for (com.aitravel.planner.itinerary.Poi p : pois) {
                                String nm = p.getName();
                                String t = classify.apply(nm);
                                if (t.equals("lodging")) lods.add(nm);
                                else if (t.equals("restaurant")) rests.add(nm);
                                else if (t.equals("transport")) trans.add(nm);
                                else atts.add(nm);
                            }
                        }
                        java.util.Map<String, Object> day = new java.util.LinkedHashMap<>();
                        day.put("title", "第 " + (i + 1) + " 天");
                        day.put("attractions", new java.util.ArrayList<>(new java.util.LinkedHashSet<>(atts)));
                        day.put("lodging", new java.util.ArrayList<>(new java.util.LinkedHashSet<>(lods)));
                        day.put("restaurants", new java.util.ArrayList<>(new java.util.LinkedHashSet<>(rests)));
                        day.put("transport", new java.util.ArrayList<>(new java.util.LinkedHashSet<>(trans)));
                        out.add(day);
                    }
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * 使用 LLM 结构化计划中的 typed POIs 直接生成 daily 数据。
     * 将 poi.type 映射为 { restaurants, lodging, attractions, transport } 四类。
     */
    private List<Map<String, Object>> convertPlanToDaily(ItineraryPlan plan) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (plan == null || plan.getDays() == null || plan.getDays().isEmpty()) return out;
        List<DayPlan> days = plan.getDays();
        for (int i = 0; i < days.size(); i++) {
            DayPlan d = days.get(i);
            List<String> atts = new ArrayList<>();
            List<String> lods = new ArrayList<>();
            List<String> rests = new ArrayList<>();
            List<String> trans = new ArrayList<>();
            List<Poi> pois = d.getPois();
            if (pois != null) {
                for (Poi p : pois) {
                    String nm = p.getName() == null ? "" : p.getName().trim();
                    if (nm.length() < 2) continue;
                    String tp = p.getType() == null ? "" : p.getType().toLowerCase();
                    // 统一类型映射
                    if (tp.contains("hotel") || tp.contains("lodg") || tp.contains("inn") || tp.contains("hostel") || tp.contains("住宿") || tp.contains("酒店")) {
                        lods.add(nm);
                    } else if (tp.contains("rest") || tp.contains("food") || tp.contains("cafe") || tp.contains("bar") || tp.contains("餐") || tp.contains("美食") || tp.contains("小吃")) {
                        rests.add(nm);
                    } else if (tp.contains("transport") || tp.contains("metro") || tp.contains("subway") || tp.contains("bus") || tp.contains("train") || tp.contains("airport") || tp.contains("车站") || tp.contains("地铁")) {
                        trans.add(nm);
                    } else {
                        // 默认归为景点（sight/museum/park 等）
                        atts.add(nm);
                    }
                }
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("title", "第 " + (i + 1) + " 天");
            day.put("attractions", new ArrayList<>(new LinkedHashSet<>(atts)));
            day.put("lodging", new ArrayList<>(new LinkedHashSet<>(lods)));
            day.put("restaurants", new ArrayList<>(new LinkedHashSet<>(rests)));
            day.put("transport", new ArrayList<>(new LinkedHashSet<>(trans)));
            out.add(day);
        }
        return out;
    }
}
