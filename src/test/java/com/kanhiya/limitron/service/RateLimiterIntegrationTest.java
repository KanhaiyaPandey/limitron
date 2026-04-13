package com.kanhiya.limitron.service;

import com.kanhiya.limitron.model.RateLimitStrategy;
import com.kanhiya.limitron.testsupport.RedisContainerTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "limitron.rate-limiter.default-limit=10",
        "limitron.rate-limiter.endpoint-limits[/login]=5",
        "limitron.rate-limiter.endpoint-limits[/search]=50",
        "limitron.rate-limiter.endpoint-limits[/concurrent]=10"
})
class RateLimiterIntegrationTest extends RedisContainerTestBase {

    @Autowired
    private RateLimiterService rateLimiterService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void tokenBucket_allowsFirstNRequests_andRejectsAfterLimitExceeded() {
        String userId = "u-token-1";
        String endpoint = "/login";

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        }

        assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isFalse();
    }

    @Test
    void tokenBucket_decrementsTokensInRedis() {
        String userId = "u-token-2";
        String endpoint = "/login";
        String key = "rate_limiter:{" + userId + "}:" + endpoint;

        assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isTrue();

        String stored = stringRedisTemplate.opsForValue().get(key);
        assertThat(stored).isNotBlank();

        String[] parts = stored.split(":");
        assertThat(parts).hasSize(2);
        long tokens = Long.parseLong(parts[0]);
        assertThat(tokens).isEqualTo(4L);
    }

    @Test
    void tokenBucket_allowsAgainAfterRefill_withoutSleepingByBackdatingLastRefillTime() {
        String userId = "u-token-3";
        String endpoint = "/login";
        String key = "rate_limiter:{" + userId + "}:" + endpoint;

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        }
        assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isFalse();

        long now = Instant.now().getEpochSecond();
        stringRedisTemplate.opsForValue().set(key, "0:" + (now - 60));

        assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isTrue();
    }

    @Test
    void tokenBucket_isolatedBetweenUsers() {
        String endpoint = "/login";
        String userA = "u-token-a";
        String userB = "u-token-b";

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiterService.allowRequest(userA, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        }
        assertThat(rateLimiterService.allowRequest(userA, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isFalse();

        assertThat(rateLimiterService.allowRequest(userB, endpoint, RateLimitStrategy.TOKEN_BUCKET)).isTrue();
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void tokenBucket_luaIsAtomic_underConcurrentRequests_neverAllowsMoreThanMaxTokens() throws Exception {
        String userId = "u-token-concurrent";
        String endpoint = "/concurrent";
        int maxTokens = 10;
        int tasks = 200;
        String key = "rate_limiter:{" + userId + "}:" + endpoint;

        long now = Instant.now().getEpochSecond();
        stringRedisTemplate.opsForValue().set(key, maxTokens + ":" + (now + Duration.ofHours(1).toSeconds()));

        ExecutorService executor = Executors.newFixedThreadPool(32);
        try {
            List<Callable<Boolean>> callables = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                callables.add(() -> rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.TOKEN_BUCKET));
            }

            List<Future<Boolean>> results = executor.invokeAll(callables);
            long allowed = countAllowed(results);

            assertThat(allowed).isEqualTo(maxTokens);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void slidingWindow_allowsUnderLimit_andRejectsWhenLimitExceeded() {
        String userId = "u-sw-1";
        String endpoint = "/login";

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.SLIDING_WINDOW)).isTrue();
        }

        assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.SLIDING_WINDOW)).isFalse();
    }

    @Test
    void slidingWindow_allowsAgainAfterWindowExpires_byUsingOldZsetScores() {
        String userId = "u-sw-2";
        String endpoint = "/login";
        String key = "rate_limiter_sw:{" + userId + "}:" + endpoint;

        long nowMillis = Instant.now().toEpochMilli();
        long windowMillis = Duration.ofMinutes(1).toMillis();

        for (int i = 0; i < 5; i++) {
            stringRedisTemplate.opsForZSet().add(key, "old-" + i, nowMillis - windowMillis - 5_000);
        }

        assertThat(rateLimiterService.allowRequest(userId, endpoint, RateLimitStrategy.SLIDING_WINDOW)).isTrue();
        assertThat(stringRedisTemplate.opsForZSet().zCard(key)).isEqualTo(1L);
    }

    @Test
    void perEndpointLimits_appliesDifferentLimits_andFallsBackToDefault() {
        String userId = "u-endpoint-1";

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiterService.allowRequest(userId, "/login", RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        }
        assertThat(rateLimiterService.allowRequest(userId, "/login", RateLimitStrategy.TOKEN_BUCKET)).isFalse();

        for (int i = 0; i < 10; i++) {
            assertThat(rateLimiterService.allowRequest(userId, "/unconfigured", RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        }
        assertThat(rateLimiterService.allowRequest(userId, "/unconfigured", RateLimitStrategy.TOKEN_BUCKET)).isFalse();

        for (int i = 0; i < 20; i++) {
            assertThat(rateLimiterService.allowRequest(userId, "/search", RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        }
    }

    private static long countAllowed(List<Future<Boolean>> results) throws InterruptedException, ExecutionException {
        long allowed = 0;
        for (Future<Boolean> f : results) {
            if (Boolean.TRUE.equals(f.get())) {
                allowed++;
            }
        }
        return allowed;
    }
}

