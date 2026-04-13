package com.kanhiya.limitron.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class RateLimiterService {

    private static final int MAX_TOKENS = 10;
    private static final int REFILL_RATE = 1;
    private static final Duration TOKEN_BUCKET_TTL = Duration.ofHours(1);

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

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> allowRequestScript;

    public RateLimiterService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        String lua = RATE_LIMITER_LUA_TEMPLATE.replace("__TTL_SECONDS__", String.valueOf(TOKEN_BUCKET_TTL.toSeconds()));
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(lua);
        this.allowRequestScript = script;
    }

    public boolean allowRequest(String userId) {
        if (!StringUtils.hasText(userId)) {
            return false;
        }

        String key = "rate_limiter:{" + userId.trim() + "}";
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
}
