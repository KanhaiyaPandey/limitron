package com.kanhiya.limitron.gateway;

import com.kanhiya.limitron.service.RateLimiterService;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class RateLimiterGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimiterGatewayFilterFactory.Config> {

    private static final String DEFAULT_USER_ID_HEADER = "X-USER-ID";

    private final RateLimiterService rateLimiterService;

    public RateLimiterGatewayFilterFactory(RateLimiterService rateLimiterService) {
        super(Config.class);
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        String headerName = StringUtils.hasText(config.getUserIdHeader()) ? config.getUserIdHeader() : DEFAULT_USER_ID_HEADER;
        String stripPrefix = StringUtils.hasText(config.getStripPrefix()) ? config.getStripPrefix() : "";

        return (exchange, chain) -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            String userId = headers.getFirst(headerName);
            String endpoint = extractEndpoint(exchange.getRequest().getPath().value(), stripPrefix);

            return Mono.fromCallable(() -> rateLimiterService.allowRequest(userId, endpoint))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(allowed -> {
                        if (allowed) {
                            return chain.filter(exchange);
                        }

                        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_PLAIN);
                        byte[] bytes = "Too Many Requests".getBytes(StandardCharsets.UTF_8);
                        return exchange.getResponse()
                                .writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
                    });
        };
    }

    @Override
    public List<String> shortcutFieldOrder() {
        return List.of("userIdHeader", "stripPrefix");
    }

    public static class Config {
        private String userIdHeader = DEFAULT_USER_ID_HEADER;
        private String stripPrefix = "/api";

        public String getUserIdHeader() {
            return userIdHeader;
        }

        public void setUserIdHeader(String userIdHeader) {
            this.userIdHeader = userIdHeader;
        }

        public String getStripPrefix() {
            return stripPrefix;
        }

        public void setStripPrefix(String stripPrefix) {
            this.stripPrefix = stripPrefix;
        }
    }

    private static String extractEndpoint(String path, String stripPrefix) {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        String normalizedPath = path.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        String normalizedPrefix = stripPrefix == null ? "" : stripPrefix.trim();
        if (StringUtils.hasText(normalizedPrefix)) {
            if (!normalizedPrefix.startsWith("/")) {
                normalizedPrefix = "/" + normalizedPrefix;
            }
            if (normalizedPath.equals(normalizedPrefix)) {
                return "/";
            }
            if (normalizedPath.startsWith(normalizedPrefix + "/")) {
                return normalizedPath.substring(normalizedPrefix.length());
            }
        }
        return normalizedPath;
    }
}
