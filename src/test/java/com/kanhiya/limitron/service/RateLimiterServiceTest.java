package com.kanhiya.limitron.service;

import com.kanhiya.limitron.config.RateLimiterProperties;
import com.kanhiya.limitron.model.RateLimitStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimiterServiceTest {

    @Test
    void allowRequest_whenUserIdNull_returnsFalse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RateLimiterProperties properties = new RateLimiterProperties();
        RateLimiterService service = new RateLimiterService(redisTemplate, RateLimitStrategy.TOKEN_BUCKET, properties);

        assertThat(service.allowRequest(null, "/login")).isFalse();
    }

    @Test
    void allowRequest_whenUserIdBlank_returnsFalse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RateLimiterProperties properties = new RateLimiterProperties();
        RateLimiterService service = new RateLimiterService(redisTemplate, RateLimitStrategy.TOKEN_BUCKET, properties);

        assertThat(service.allowRequest("   ", "/login")).isFalse();
    }

    @Test
    void allowRequest_whenEndpointNull_usesDefaultEndpointKeyAndDefaultLimit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);

        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setDefaultLimit(7);
        RateLimiterService service = new RateLimiterService(redisTemplate, RateLimitStrategy.TOKEN_BUCKET, properties);

        assertThat(service.allowRequest("user-1", null, RateLimitStrategy.TOKEN_BUCKET)).isTrue();
    }

    @Test
    void allowRequest_whenEndpointHasCustomLimit_appliesThatLimit() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        AtomicReference<List<String>> capturedKeys = new AtomicReference<>();
        AtomicReference<Object[]> capturedArgs = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            capturedKeys.set(invocation.getArgument(1));
            Object[] full = invocation.getArguments();
            Object[] args = new Object[full.length - 2];
            System.arraycopy(full, 2, args, 0, args.length);
            capturedArgs.set(args);
            return 1L;
        });

        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setDefaultLimit(10);
        properties.setEndpointLimits(Map.of("/login", 5));

        RateLimiterService service = new RateLimiterService(redisTemplate, RateLimitStrategy.TOKEN_BUCKET, properties);

        assertThat(service.allowRequest("user-1", "/login?foo=bar", RateLimitStrategy.TOKEN_BUCKET)).isTrue();
        assertThat(capturedKeys.get()).containsExactly("rate_limiter:{user-1}:/login");
        assertThat(capturedArgs.get()).isNotNull();
        assertThat(capturedArgs.get()[0]).isEqualTo("5");
    }

    @Test
    void allowRequest_whenRedisThrows_failsGracefullyWithFalse() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate)
                .execute(any(RedisScript.class), anyList(), any(), any(), any());

        RateLimiterProperties properties = new RateLimiterProperties();
        RateLimiterService service = new RateLimiterService(redisTemplate, RateLimitStrategy.TOKEN_BUCKET, properties);

        assertThat(service.allowRequest("user-1", "/login", RateLimitStrategy.TOKEN_BUCKET)).isFalse();
    }
}
