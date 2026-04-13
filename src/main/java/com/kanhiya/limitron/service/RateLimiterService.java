package com.kanhiya.limitron.service;

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
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RateLimiterService {

    private static final int MAX_TOKENS = 10;
    private static final int REFILL_RATE = 1;
    private static final Duration TOKEN_BUCKET_TTL = Duration.ofHours(1);

    private static final int SLIDING_WINDOW_LIMIT = 10;
    private static final Duration SLIDING_WINDOW_SIZE = Duration.ofSeconds(60);
    private static final Duration SLIDING_WINDOW_TTL = SLIDING_WINDOW_SIZE.plusSeconds(5);

    private static final String RATE_LIMITER_LUA_TEMPLATE = """
            -- KEYS[1] = rate_limiter:{userId}
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
            -- KEYS[1] = rate_limiter_sw:{userId}
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

    public RateLimiterService(
            StringRedisTemplate stringRedisTemplate,
            @Value("${limitron.rate-limiter.strategy:TOKEN_BUCKET}") RateLimitStrategy rateLimitStrategy
    ) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitStrategy = rateLimitStrategy == null ? RateLimitStrategy.TOKEN_BUCKET : rateLimitStrategy;

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
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return false;
        }

        return allowRequest(normalizedUserId, rateLimitStrategy);
    }

    public boolean allowRequest(String userId, RateLimitStrategy strategy) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }

        RateLimitStrategy effectiveStrategy = strategy == null ? rateLimitStrategy : strategy;
        if (effectiveStrategy == null) {
            effectiveStrategy = RateLimitStrategy.TOKEN_BUCKET;
        }

        return switch (effectiveStrategy) {
            case SLIDING_WINDOW -> allowRequestSlidingWindow(userId);
            case TOKEN_BUCKET -> allowRequestTokenBucket(userId);
        };
    }

    public boolean allowRequestSlidingWindow(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return false;
        }

        String key = "rate_limiter_sw:{" + normalizedUserId + "}";
        long nowMillis = Instant.now().toEpochMilli();

        String member = nowMillis + ":" + Long.toUnsignedString(ThreadLocalRandom.current().nextLong());

        Long result = stringRedisTemplate.execute(
                allowRequestSlidingWindowScript,
                List.of(key),
                String.valueOf(SLIDING_WINDOW_LIMIT),
                String.valueOf(SLIDING_WINDOW_SIZE.toMillis()),
                String.valueOf(nowMillis),
                member,
                String.valueOf(SLIDING_WINDOW_TTL.toSeconds())
        );

        return result != null && result == 1L;
    }

    private boolean allowRequestTokenBucket(String userId) {
        String normalizedUserId = normalizeUserId(userId);
        if (normalizedUserId == null) {
            return false;
        }

        String key = "rate_limiter:{" + normalizedUserId + "}";
        long now = Instant.now().getEpochSecond();

        Long result = stringRedisTemplate.execute(
                allowRequestScript,
                List.of(key),
                String.valueOf(MAX_TOKENS),
                String.valueOf(REFILL_RATE),
                String.valueOf(now)
        );

        return result != null && result == 1L;
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
}
