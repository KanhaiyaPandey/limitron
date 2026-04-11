package com.kanhiya.limitron.controller;

import com.kanhiya.limitron.service.RateLimiterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RateLimiterController {

    private final RateLimiterService rateLimiterService;

    public RateLimiterController(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @GetMapping("/request")
    public ResponseEntity<String> request(@RequestParam(required = false) String userId) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.badRequest().body("userId is required");
        }

        boolean allowed = rateLimiterService.allowRequest(userId);
        if (allowed) {
            return ResponseEntity.ok("Request Allowed");
        }

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too Many Requests");
    }
}
