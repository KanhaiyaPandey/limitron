package com.kanhiya.limitron.service;

import com.kanhiya.limitron.config.RateLimiterProperties;
import com.kanhiya.limitron.model.RateLimitStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RateLimiterService {

    private static final Duration DEFAULT_LIMIT_WINDOW = Duration.ofMinutes(1);
    private static final Duration TOKEN_BUCKET_TTL = Duration.ofHours(1);

    private static final Duration SLIDING_WINDOW_SIZE = DEFAULT_LIMIT_WINDOW;
    private static final Duration SLIDING_WINDOW_TTL = SLIDING_WINDOW_SIZE.plusSeconds(5);

    private static final String RATE_LIMITER_LUA_TEMPLATE = """
            -- KEYS[1] = rate_limiter:{userId}:{endpoint}
            -- ARGV[1] = maxTokens
            -- ARGV[2] = refillRate (tokens per second)
            -- ARGV[3] = currentTime (epoch seconds)
            local key = KEYS[1]
            local maxTokens = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local value = redis.call('GET', key)
            local tokens = maxTokens
            local lastRefillTime = now

            if value then
              local t, l = string.match(value, "^(%-?%d+):(%-?%d+)$")
              if t and l then
                tokens = tonumber(t)
                lastRefillTime = tonumber(l)
              end
            end

            if tokens == nil or lastRefillTime == nil then
              tokens = maxTokens
              lastRefillTime = now
            end

            if now > lastRefillTime then
              local elapsed = now - lastRefillTime
              local refill = elapsed * refillRate
              tokens = math.min(maxTokens, tokens + refill)
              lastRefillTime = now
            end

            local allowed = 0
            if tokens > 0 then
              tokens = tokens - 1
              allowed = 1
            end

            redis.call('SET', key, tostring(tokens) .. ":" .. tostring(lastRefillTime))
            redis.call('EXPIRE', key, __TTL_SECONDS__)
            return allowed
            """;

    private static final String SLIDING_WINDOW_LUA = """
            -- KEYS[1] = rate_limiter_sw:{userId}:{endpoint}
            -- ARGV[1] = limit
            -- ARGV[2] = windowMillis
            -- ARGV[3] = nowMillis
            -- ARGV[4] = member
            -- ARGV[5] = ttlSeconds
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local windowMillis = tonumber(ARGV[2])
            local nowMillis = tonumber(ARGV[3])
            local member = ARGV[4]
            local ttlSeconds = tonumber(ARGV[5])

            local minScore = nowMillis - windowMillis
            redis.call('ZREMRANGEBYSCORE', key, 0, minScore)

            local count = redis.call('ZCARD', key)
            if count < limit then
              redis.call('ZADD', key, nowMillis, member)
              redis.call('EXPIRE', key, ttlSeconds)
              return 1
            end

            redis.call('EXPIRE', key, ttlSeconds)
            return 0
            """;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> allowRequestScript;
    private final RedisScript<Long> allowRequestSlidingWindowScript;
    private final RateLimitStrategy rateLimitStrategy;
    private final RateLimiterProperties rateLimiterProperties;

    public RateLimiterService(
            StringRedisTemplate stringRedisTemplate,
            @Value("${limitron.rate-limiter.strategy:TOKEN_BUCKET}") RateLimitStrategy rateLimitStrategy,
            RateLimiterProperties rateLimiterProperties
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitStrategy = rateLimitStrategy == null ? RateLimitStrategy.TOKEN_BUCKET : rateLimitStrategy;
        this.rateLimiterProperties = rateLimiterProperties;

        String lua = RATE_LIMITER_LUA_TEMPLATE.replace("__TTL_SECONDS__", String.valueOf(TOKEN_BUCKET_TTL.toSeconds()));
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(lua);
        this.allowRequestScript = script;

        DefaultRedisScript<Long> slidingWindowScript = new DefaultRedisScript<>();
        slidingWindowScript.setResultType(Long.class);
        slidingWindowScript.setScriptText(SLIDING_WINDOW_LUA);
        this.allowRequestSlidingWindowScript = slidingWindowScript;
    }

    public boolean allowRequest(String userId) {
        return allowRequest(userId, null, rateLimitStrategy);
    }

    public boolean allowRequest(String userId, RateLimitStrategy strategy) {
        return allowRequest(userId, null, strategy);
    }

    public boolean allowRequest(String userId, String endpoint) {
        return allowRequest(userId, endpoint, rateLimitStrategy);
    }

    public boolean allowRequest(String userId, String endpoint, RateLimitStrategy strategy) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return false;
        }

        String normalizedEndpoint = normalizeEndpoint(endpoint);
        if (normalizedEndpoint == null) {
            normalizedEndpoint = "/default";
        }

        int limit = endpointLimit(normalizedEndpoint);

        RateLimitStrategy effectiveStrategy = strategy == null ? rateLimitStrategy : strategy;
        if (effectiveStrategy == null) {
            effectiveStrategy = RateLimitStrategy.TOKEN_BUCKET;
        }

        return switch (effectiveStrategy) {
            case SLIDING_WINDOW -> allowRequestSlidingWindow(normalizedUserId, normalizedEndpoint, limit);
            case TOKEN_BUCKET -> allowRequestTokenBucket(normalizedUserId, normalizedEndpoint, limit);
        };
    }

    public boolean allowRequestSlidingWindow(String userId) {
        return allowRequest(userId, null, RateLimitStrategy.SLIDING_WINDOW);
    }

    public boolean allowRequestSlidingWindow(String userId, String endpoint) {
        return allowRequest(userId, endpoint, RateLimitStrategy.SLIDING_WINDOW);
    }

    private boolean allowRequestSlidingWindow(String userId, String endpoint, int limit) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return false;
        }

        String normalizedEndpoint = normalizeEndpoint(endpoint);
        if (normalizedEndpoint == null) {
            normalizedEndpoint = "/default";
        }

        String key = "rate_limiter_sw:{" + normalizedUserId + "}:" + normalizedEndpoint;
        long nowMillis = Instant.now().toEpochMilli();

        String member = nowMillis + ":" + Long.toUnsignedString(ThreadLocalRandom.current().nextLong());

        Long result;
        try {
            result = stringRedisTemplate.execute(
                    allowRequestSlidingWindowScript,
                    List.of(key),
                    String.valueOf(limit),
                    String.valueOf(SLIDING_WINDOW_SIZE.toMillis()),
                    String.valueOf(nowMillis),
                    member,
                    String.valueOf(SLIDING_WINDOW_TTL.toSeconds())
            );
        } catch (RuntimeException ex) {
            return false;
        }

        return result != null && result == 1L;
    }

    private boolean allowRequestTokenBucket(String userId, String endpoint, int maxTokens) {
        String key = "rate_limiter:{" + userId + "}:" + endpoint;
        long now = Instant.now().getEpochSecond();

        double refillRatePerSecond = refillRatePerSecond(maxTokens, DEFAULT_LIMIT_WINDOW);

        Long result;
        try {
            result = stringRedisTemplate.execute(
                    allowRequestScript,
                    List.of(key),
                    String.valueOf(maxTokens),
                    String.valueOf(refillRatePerSecond),
                    String.valueOf(now)
            );
        } catch (RuntimeException ex) {
            return false;
        }

        return result != null && result == 1L;
    }

    private int endpointLimit(String endpoint) {
        int defaultLimit = rateLimiterProperties == null ? 10 : rateLimiterProperties.getDefaultLimit();
        if (defaultLimit < 1) {
            defaultLimit = 1;
        }

        Map<String, Integer> configured = rateLimiterProperties == null ? null : rateLimiterProperties.getEndpointLimits();
        if (configured == null || configured.isEmpty()) {
            return defaultLimit;
        }

        Integer value = configured.get(endpoint);
        if (value == null) {
            value = configured.get(endpoint.startsWith("/") ? endpoint.substring(1) : ("/" + endpoint));
        }
        if (value == null || value < 1) {
            return defaultLimit;
        }
        return value;
    }

    private static double refillRatePerSecond(int maxTokens, Duration window) {
        long windowSeconds = window == null ? 60 : Math.max(1L, window.toSeconds());
        return maxTokens / (double) windowSeconds;
    }

    private static String normalizeUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return null;
        }
        String normalized = userId.trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return normalized;
    }

    private static String normalizeEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            return null;
        }
        String normalized = endpoint.trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        int queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if ("/".equals(normalized)) {
            return normalized;
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
