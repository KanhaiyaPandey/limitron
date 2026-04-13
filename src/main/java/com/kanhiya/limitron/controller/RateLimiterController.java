package com.kanhiya.limitron.controller;

import com.kanhiya.limitron.service.RateLimiterService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
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
    public ResponseEntity<String> request(
            @RequestParam(required = false) String userId,
            ServerHttpRequest request
    ) {
        if (!StringUtils.hasText(userId)) {
            return ResponseEntity.badRequest().body("userId is required");
        }

        String endpoint = request == null ? null : request.getPath().value();
        boolean allowed = rateLimiterService.allowRequest(userId, endpoint);
        if (allowed) {
            return ResponseEntity.ok("Request Allowed");
        }

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Too Many Requests");
    }
}
