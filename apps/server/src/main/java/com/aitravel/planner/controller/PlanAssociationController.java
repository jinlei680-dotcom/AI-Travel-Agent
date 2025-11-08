package com.aitravel.planner.controller;

import com.aitravel.planner.model.Plan;
import com.aitravel.planner.repo.PlanRepository;
import com.aitravel.planner.repo.ConversationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/plans")
@CrossOrigin(origins = "*", allowCredentials = "false")
public class PlanAssociationController {
    private final PlanRepository plans;
    private final ConversationRepository conversations;

    public PlanAssociationController(PlanRepository plans, ConversationRepository conversations) {
        this.plans = plans;
        this.conversations = conversations;
    }

    private Optional<com.aitravel.planner.auth.JwtUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof com.aitravel.planner.auth.JwtUser)) return Optional.empty();
        return Optional.of((com.aitravel.planner.auth.JwtUser) auth.getPrincipal());
    }

    @PostMapping("/create-with-conversation")
    public ResponseEntity<?> createWithConversation(@RequestBody CreatePlanWithConversationRequest req) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));

        if (req.destination() == null || req.startDate() == null || req.endDate() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "destination、startDate、endDate 不能为空"));
        }
        if (!req.endDate().isEqual(req.startDate()) && req.endDate().isBefore(req.startDate())) {
            return ResponseEntity.badRequest().body(Map.of("error", "结束日期必须不早于开始日期"));
        }

        // 校验并设置会话归属
        if (req.conversationId() != null) {
            var copt = conversations.findById(req.conversationId());
            if (copt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
            }
            if (!copt.get().getOwnerId().equals(cu.get().getId())) {
                return ResponseEntity.status(403).body(Map.of("error", "无权限关联该会话"));
            }
        }

        // ownerId 若传入需与当前用户一致
        if (req.ownerId() != null && !req.ownerId().equals(cu.get().getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "不能为其他用户创建行程"));
        }

        Plan p = new Plan();
        p.setOwnerId(cu.get().getId());
        p.setDestination(req.destination());
        p.setStartDate(req.startDate());
        p.setEndDate(req.endDate());
        p.setBudgetAmount(req.budgetAmount());
        p.setBudgetCurrency(req.budgetCurrency());
        p.setTags(req.tags());
        p.setStatus("draft");
        if (req.conversationId() != null) {
            p.setConversationId(req.conversationId());
        }
        int daysCount = (int) (java.time.temporal.ChronoUnit.DAYS.between(req.startDate(), req.endDate()) + 1);
        p.setTotalDays(Math.max(daysCount, 1));

        Plan saved = plans.save(p);
        return ResponseEntity.ok(saved);
    }

    public record CreatePlanWithConversationRequest(
            UUID ownerId,
            String destination,
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal budgetAmount,
            String budgetCurrency,
            String[] tags,
            UUID conversationId
    ) {}
}
