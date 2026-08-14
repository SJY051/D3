package com.ddd.d3.platform.gateway;

import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class CorrelationIdWebFilter implements WebFilter {

    static final String HEADER_NAME = "X-Correlation-Id";
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = normalized(exchange.getRequest().getHeaders().getFirst(HEADER_NAME));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER_NAME, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(HEADER_NAME, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }

    private static String normalized(String candidate) {
        return candidate != null && SAFE_VALUE.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
