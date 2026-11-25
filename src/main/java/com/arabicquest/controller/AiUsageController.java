// Admin-only endpoint exposing today's AI provider call/token usage and configured cost caps.
package com.arabicquest.controller;

import com.arabicquest.service.AiUsageGuard;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AiUsageController {

    private final AiUsageGuard aiUsageGuard;

    public AiUsageController(AiUsageGuard aiUsageGuard) {
        this.aiUsageGuard = aiUsageGuard;
    }

    @GetMapping("/ai/usage")
    public ResponseEntity<Map<String, Object>> getDailyAiUsage() {
        return ResponseEntity.ok(aiUsageGuard.getDailyUsageSnapshot());
    }
}
