package com.aitravel.planner.controller;

import com.aitravel.planner.model.Day;
import com.aitravel.planner.model.Plan;
import com.aitravel.planner.repo.DayRepository;
import com.aitravel.planner.repo.PlanRepository;
import com.aitravel.planner.service.PlanningService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/plans")
@CrossOrigin(origins = "*", allowCredentials = "false")
public class PlansController {
    private final PlanRepository plans;
    private final DayRepository days;
    private final PlanningService planning;

    public PlansController(PlanRepository plans, DayRepository days, PlanningService planning) {
        this.plans = plans;
        this.days = days;
        this.planning = planning;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreatePlanRequest req) {
        if (req.ownerId() == null || req.destination() == null || req.startDate() == null || req.endDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "ownerId、destination、startDate、endDate 不能为空"));
        }
        if (!req.endDate().isEqual(req.startDate()) && req.endDate().isBefore(req.startDate())) {
            return ResponseEntity.badRequest().body(Map.of("error", "结束日期必须不早于开始日期"));
        }
        Plan p = new Plan();
        p.setOwnerId(req.ownerId());
        p.setDestination(req.destination());
        p.setStartDate(req.startDate());
        p.setEndDate(req.endDate());
        p.setBudgetAmount(req.budgetAmount());
        p.setBudgetCurrency(req.budgetCurrency());
        p.setTags(req.tags());
        p.setStatus("draft");
        // totalDays 将在生成 days 时更新；若用户仅创建草稿也可先置为区间天数
        int daysCount = (int) (java.time.temporal.ChronoUnit.DAYS.between(req.startDate(), req.endDate()) + 1);
        p.setTotalDays(Math.max(daysCount, 1));
        Plan saved = plans.save(p);
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/{id}/generate")
    public ResponseEntity<?> generate(@PathVariable("id") UUID id) {
        Optional<Plan> popt = plans.findById(id);
        if (popt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "行程不存在"));
        }
        Plan plan = popt.get();
        List<Day> generated = planning.generateDaysForPlan(plan);
        return ResponseEntity.ok(Map.of(
                "plan", plan,
                "days", generated
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") UUID id) {
        Optional<Plan> popt = plans.findById(id);
        if (popt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "行程不存在"));
        }
        Plan plan = popt.get();
        List<Day> ds = days.findByPlanIdOrderByIndexAsc(id);
        return ResponseEntity.ok(Map.of("plan", plan, "days", ds));
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "ownerId", required = false) UUID ownerId) {
        List<Plan> res = ownerId == null ? plans.findAll() : plans.findByOwnerId(ownerId);
        return ResponseEntity.ok(res);
    }

    public record CreatePlanRequest(
            UUID ownerId,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal budgetAmount,
            String budgetCurrency,
            String[] tags
    ) {}
}