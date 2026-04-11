package com.kanhiya.limitron.service;

import com.kanhiya.limitron.model.TokenBucket;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Core rate limiter service based on a token bucket stored in Redis.
 */
@Service
public class RateLimiterService {

    private static final int MAX_TOKENS = 10;
    private static final int REFILL_RATE = 1;
    private static final Duration TOKEN_BUCKET_TTL = Duration.ofHours(1);

    private final RedisTemplate<String, Object> redisTemplate;

    public RateLimiterService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public boolean allowRequest(String userId) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }

        String key = "rate_limiter:" + userId.trim();
        ValueOperations<String, Object> ops = redisTemplate.opsForValue();

        TokenBucket bucket = getTokenBucket(key, ops.get(key));
        long now = Instant.now().getEpochSecond();

        // Refill tokens based on elapsed time
        long elapsed = now - bucket.getLastRefillTime();
        if (elapsed > 0) {
            long refill = elapsed * REFILL_RATE;
            int updatedTokens = (int) Math.min(MAX_TOKENS, bucket.getTokens() + refill);
            bucket.setTokens(updatedTokens);
        }

        if (bucket.getTokens() > 0) {
            bucket.setTokens(bucket.getTokens() - 1);
            bucket.setLastRefillTime(now);
            ops.set(key, bucket, TOKEN_BUCKET_TTL);
            return true;
        }

        return false;
    }

    private TokenBucket getTokenBucket(String key, Object storedValue) {
        if (storedValue instanceof TokenBucket tokenBucket) {
            return tokenBucket;
        }

        if (storedValue instanceof Map<?, ?> map) {
            Integer tokens = toInteger(map.get("tokens"));
            Long lastRefillTime = toLong(map.get("lastRefillTime"));
            if (tokens != null && lastRefillTime != null) {
                return new TokenBucket(tokens, lastRefillTime);
            }
        }

        return new TokenBucket(MAX_TOKENS, Instant.now().getEpochSecond());
    }

    private Integer toInteger(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
