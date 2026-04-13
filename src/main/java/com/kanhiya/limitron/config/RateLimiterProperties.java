package com.kanhiya.limitron.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "limitron.rate-limiter")
public class RateLimiterProperties {

    private int defaultLimit = 10;

    private Map<String, Integer> endpointLimits = new HashMap<>();

    public int getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public Map<String, Integer> getEndpointLimits() {
        return endpointLimits;
    }

    public void setEndpointLimits(Map<String, Integer> endpointLimits) {
        this.endpointLimits = endpointLimits;
    }
}

