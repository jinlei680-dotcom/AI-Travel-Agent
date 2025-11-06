package com.aitravel.planner.service;

import com.aitravel.planner.model.Day;
import com.aitravel.planner.model.Plan;
import com.aitravel.planner.repo.DayRepository;
import com.aitravel.planner.repo.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlanningService {
    private final PlanRepository plans;
    private final DayRepository days;

    public PlanningService(PlanRepository plans, DayRepository days) {
        this.plans = plans;
        this.days = days;
    }

    @Transactional
    public List<Day> generateDaysForPlan(Plan plan) {
        long n = ChronoUnit.DAYS.between(plan.getStartDate(), plan.getEndDate()) + 1; // inclusive
        if (n <= 0) {
            throw new IllegalArgumentException("结束日期必须不早于开始日期");
        }
        // 清理旧的 days（如有）
        var old = days.findByPlanIdOrderByIndexAsc(plan.getId());
        if (!old.isEmpty()) {
            days.deleteAll(old);
        }
        List<Day> created = new ArrayList<>();
        LocalDate d = plan.getStartDate();
        for (int i = 0; i < n; i++) {
            Day day = new Day();
            day.setPlanId(plan.getId());
            day.setIndex(i);
            day.setDate(d.plusDays(i));
            created.add(days.save(day));
        }
        plan.setTotalDays((int) n);
        plan.setStatus("planned");
        plans.save(plan);
        return created;
    }
}