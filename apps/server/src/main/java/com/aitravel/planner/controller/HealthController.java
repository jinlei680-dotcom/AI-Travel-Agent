package com.aitravel.planner.controller;

import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", 0);
        body.put("message", "ok");
        Map<String, Object> data = new HashMap<>();
        data.put("service", "ai-travel-planner-server");
        data.put("status", "healthy");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}