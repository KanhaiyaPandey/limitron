package com.kanhiya.limitron.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a token bucket for rate limiting.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TokenBucket {

    private int tokens;
    private long lastRefillTime;
}
