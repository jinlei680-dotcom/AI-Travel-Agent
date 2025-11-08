package com.aitravel.planner.controller;

import com.aitravel.planner.auth.JwtUser;
import com.aitravel.planner.model.Conversation;
import com.aitravel.planner.model.Message;
import com.aitravel.planner.repo.ConversationRepository;
import com.aitravel.planner.repo.MessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
@CrossOrigin(origins = "*", allowCredentials = "false")
public class ConversationsController {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final com.aitravel.planner.service.LlmService llm;

    public ConversationsController(ConversationRepository conversations, MessageRepository messages, com.aitravel.planner.service.LlmService llm) {
        this.conversations = conversations;
        this.messages = messages;
        this.llm = llm;
    }

    private Optional<JwtUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof JwtUser)) return Optional.empty();
        return Optional.of((JwtUser) auth.getPrincipal());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        Conversation c = new Conversation();
        c.setOwnerId(cu.get().getId());
        c.setTitle((req.title() == null || req.title().isBlank()) ? "新会话" : req.title());
        c.setStatus("active");
        c.setCreatedAt(OffsetDateTime.now());
        c.setUpdatedAt(OffsetDateTime.now());
        Conversation saved = conversations.save(c);
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public ResponseEntity<?> list() {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        List<Conversation> list = conversations.findByOwnerIdOrderByUpdatedAtDesc(cu.get().getId());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable("id") UUID id) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        Optional<Conversation> copt = conversations.findById(id);
        if (copt.isEmpty() || !copt.get().getOwnerId().equals(cu.get().getId())) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在或无权限"));
        }
        Conversation c = copt.get();
        List<Message> ms = messages.findByConversationIdOrderByCreatedAtAsc(id);
        return ResponseEntity.ok(Map.of("conversation", c, "messages", ms));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<?> appendMessage(@PathVariable("id") UUID id, @RequestBody AppendMessageRequest req) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        Optional<Conversation> copt = conversations.findById(id);
        if (copt.isEmpty() || !copt.get().getOwnerId().equals(cu.get().getId())) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在或无权限"));
        }
        if (req.content() == null || req.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "content 不能为空"));
        }
        Message m = new Message();
        m.setConversationId(id);
        m.setRole((req.role() == null || req.role().isBlank()) ? "user" : req.role());
        m.setContent(req.content());
        m.setTokens(req.tokens());
        Message saved = messages.save(m);
        Conversation c = copt.get();
        c.setUpdatedAt(OffsetDateTime.now());
        conversations.save(c);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<?> listMessages(@PathVariable("id") UUID id) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        Optional<Conversation> copt = conversations.findById(id);
        if (copt.isEmpty() || !copt.get().getOwnerId().equals(cu.get().getId())) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在或无权限"));
        }
        List<Message> ms = messages.findByConversationIdOrderByCreatedAtAsc(id);
        return ResponseEntity.ok(ms);
    }

    public record ChatPlanRequest(String text, String city) {}

    /**
     * 基于会话历史进行上下文规划：返回原始文本(rawText)与结构化计划(plan)。
     */
    @PostMapping("/{id}/chat-plan")
    public ResponseEntity<?> chatPlan(@PathVariable("id") UUID id, @RequestBody ChatPlanRequest req) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(java.util.Map.of("error", "未认证"));
        var copt = conversations.findById(id);
        if (copt.isEmpty() || !copt.get().getOwnerId().equals(cu.get().getId())) {
            return ResponseEntity.status(404).body(java.util.Map.of("error", "会话不存在或无权限"));
        }
        if (req == null || req.text() == null || req.text().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "text 不能为空"));
        }
        java.util.List<Message> hist = messages.findByConversationIdOrderByCreatedAtAsc(id);
        java.util.List<java.util.Map<String, String>> prev = new java.util.ArrayList<>();
        prev.add(java.util.Map.of("role", "system", "content", "你是行程规划助手。尽量给出完整、可执行的建议。"));
        for (Message m : hist) {
            String role = (m.getRole() == null || m.getRole().isBlank()) ? "user" : m.getRole();
            String content = m.getContent() == null ? "" : m.getContent();
            prev.add(java.util.Map.of("role", role, "content", content));
        }
        java.util.Optional<com.aitravel.planner.service.LlmService.PlanResult> resOpt = llm.planWithRawWithContext(req.text(), req.city(), prev);
        if (resOpt.isPresent()) {
            var pr = resOpt.get();
            String raw = pr.getRawText();
            try {
                raw = com.aitravel.planner.util.BudgetRawTextNormalizer.normalize(raw);
            } catch (Exception ignored) {}
            com.aitravel.planner.itinerary.ItineraryPlan plan = pr.getPlan();
            // 不再以摘要兜底，保持原文（可能为空）
            return ResponseEntity.ok(java.util.Map.of("plan", plan, "rawText", raw));
        }
        return ResponseEntity.status(502).body(java.util.Map.of("error", "LLM 不可用或超时"));
    }

    public record BudgetAdjustRequest(java.math.BigDecimal usedAmount, String currency, String text, String city) {}

    /**
     * 基于已使用花费动态调整后续行程：复用会话上下文，向模型明确预算约束，并返回结构化计划与原文。
     */
    @PostMapping("/{id}/budget-adjust")
    public ResponseEntity<?> budgetAdjust(@PathVariable("id") UUID id, @RequestBody BudgetAdjustRequest req) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(java.util.Map.of("error", "未认证"));
        var copt = conversations.findById(id);
        if (copt.isEmpty() || !copt.get().getOwnerId().equals(cu.get().getId())) {
            return ResponseEntity.status(404).body(java.util.Map.of("error", "会话不存在或无权限"));
        }
        // 构造调整提示：包含已使用花费与可选补充文本
        StringBuilder sb = new StringBuilder();
        if (req.usedAmount() != null && req.currency() != null && !req.currency().isBlank()) {
            sb.append("我已经使用了 ").append(req.usedAmount().toPlainString()).append(" ").append(req.currency()).append(" 的预算。");
        }
        if (req.text() != null && !req.text().isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(req.text());
        }
        if (sb.length() == 0) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "请至少提供 usedAmount/currency 或补充说明文本"));
        }
        // 会话历史
        java.util.List<Message> hist = messages.findByConversationIdOrderByCreatedAtAsc(id);
        java.util.List<java.util.Map<String, String>> prev = new java.util.ArrayList<>();
        prev.add(java.util.Map.of("role", "system", "content", "你是行程规划助手。尽量给出完整、可执行的建议。在涉及预算时，请提供 baseBudget {amount,currency} 并合理优化后续天安排。"));
        for (Message m : hist) {
            String role = (m.getRole() == null || m.getRole().isBlank()) ? "user" : m.getRole();
            String content = m.getContent() == null ? "" : m.getContent();
            prev.add(java.util.Map.of("role", role, "content", content));
        }
        var resOpt = llm.planWithRawWithContext(sb.toString(), req.city(), prev);
        if (resOpt.isPresent()) {
            var pr = resOpt.get();
            String raw = pr.getRawText();
            try {
                raw = com.aitravel.planner.util.BudgetRawTextNormalizer.normalize(raw);
            } catch (Exception ignored) {}
            com.aitravel.planner.itinerary.ItineraryPlan plan = pr.getPlan();
            // 修改：不再使用摘要回填 rawText，严格返回原始文本（可能为空）
            return ResponseEntity.ok(java.util.Map.of("plan", plan, "rawText", raw));
        }
        return ResponseEntity.status(502).body(java.util.Map.of("error", "LLM 不可用或超时"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable("id") UUID id) {
        var cu = currentUser();
        if (cu.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        Optional<Conversation> copt = conversations.findById(id);
        if (copt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        }
        if (!copt.get().getOwnerId().equals(cu.get().getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "无权限删除该会话"));
        }
        // 先删除该会话的消息，再删除会话
        try { messages.deleteByConversationId(id); } catch (Exception ignored) {}
        conversations.deleteById(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record CreateRequest(String title) {}
    public record AppendMessageRequest(String role, String content, Integer tokens) {}
}
