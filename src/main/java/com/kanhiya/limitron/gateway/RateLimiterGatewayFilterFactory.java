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

        return (exchange, chain) -> {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            String userId = headers.getFirst(headerName);

            return Mono.fromCallable(() -> rateLimiterService.allowRequest(userId))
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
        return List.of("userIdHeader");
    }

    public static class Config {
        private String userIdHeader = DEFAULT_USER_ID_HEADER;

        public String getUserIdHeader() {
            return userIdHeader;
        }

        public void setUserIdHeader(String userIdHeader) {
            this.userIdHeader = userIdHeader;
        }
    }
}

