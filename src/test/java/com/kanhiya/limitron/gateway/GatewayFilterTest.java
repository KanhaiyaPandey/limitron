package com.kanhiya.limitron.gateway;

import com.kanhiya.limitron.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayFilterTest {

    @Test
    void filter_whenAllowed_forwardsRequest() {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.allowRequest(eq("user-1"), eq("/login"))).thenReturn(true);

        RateLimiterGatewayFilterFactory factory = new RateLimiterGatewayFilterFactory(rateLimiterService);
        RateLimiterGatewayFilterFactory.Config config = new RateLimiterGatewayFilterFactory.Config();
        config.setUserIdHeader("X-USER-ID");
        config.setStripPrefix("/api");

        GatewayFilter filter = factory.apply(config);

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/login")
                .header("X-USER-ID", "user-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void filter_whenBlocked_returns429_andDoesNotForward() {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);
        when(rateLimiterService.allowRequest(eq("user-1"), eq("/login"))).thenReturn(false);

        RateLimiterGatewayFilterFactory factory = new RateLimiterGatewayFilterFactory(rateLimiterService);
        GatewayFilter filter = factory.apply(new RateLimiterGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/login")
                .header("X-USER-ID", "user-1")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain, never()).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void filter_whenUserIdHeaderMissing_returns429() {
        RateLimiterService rateLimiterService = mock(RateLimiterService.class);

        RateLimiterGatewayFilterFactory factory = new RateLimiterGatewayFilterFactory(rateLimiterService);
        GatewayFilter filter = factory.apply(new RateLimiterGatewayFilterFactory.Config());

        MockServerHttpRequest request = MockServerHttpRequest.get("/api/login").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        ArgumentCaptor<String> userIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> endpointCaptor = ArgumentCaptor.forClass(String.class);
        verify(rateLimiterService).allowRequest(userIdCaptor.capture(), endpointCaptor.capture());

        assertThat(userIdCaptor.getValue()).isNull();
        assertThat(endpointCaptor.getValue()).isEqualTo("/login");
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
