package com.kanhiya.limitron.controller;

import com.kanhiya.limitron.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterControllerTest {

    @Test
    void request_whenAllowed_returns200() {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.allowRequest(eq("user-1"), anyString())).thenReturn(true);

        WebTestClient webTestClient = WebTestClient.bindToController(new RateLimiterController(rateLimiterService)).build();

        webTestClient.get()
                .uri("/api/request?userId=user-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("Request Allowed");
    }

    @Test
    void request_whenBlocked_returns429() {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.allowRequest(eq("user-1"), anyString())).thenReturn(false);

        WebTestClient webTestClient = WebTestClient.bindToController(new RateLimiterController(rateLimiterService)).build();

        webTestClient.get()
                .uri("/api/request?userId=user-1")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectBody(String.class).isEqualTo("Too Many Requests");
    }

    @Test
    void request_whenUserIdMissing_returns400() {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        WebTestClient webTestClient = WebTestClient.bindToController(new RateLimiterController(rateLimiterService)).build();

        webTestClient.get()
                .uri("/api/request")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class).isEqualTo("userId is required");

        verify(rateLimiterService, never()).allowRequest(anyString(), anyString());
    }
}
