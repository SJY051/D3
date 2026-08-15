package com.ddd.d3.platform.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class BattleWebSocketCredentialWebFilter implements WebFilter {

    static final String APPLICATION_PROTOCOL = "d3.battle.v2";
    static final String V3_APPLICATION_PROTOCOL = "d3.battle.v3";
    static final String CREDENTIAL_PREFIX = "d3.jwt.";
    private static final Set<String> APPLICATION_PROTOCOLS =
            Set.of(APPLICATION_PROTOCOL, V3_APPLICATION_PROTOCOL);
    private static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final Pattern MATCH_PATH = Pattern.compile(
            "^/ws/v1/battle/matches/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");
    private static final Pattern COMPACT_JWT = Pattern.compile(
            "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    private static final int MAX_TOKEN_LENGTH = 4096;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isBattleWebSocketHandshake(exchange)) {
            return chain.filter(exchange);
        }

        List<String> protocols = protocols(exchange.getRequest().getHeaders());
        List<String> applicationProtocols = protocols.stream()
                .filter(APPLICATION_PROTOCOLS::contains)
                .toList();
        List<String> credentials = protocols.stream()
                .filter(protocol -> protocol.startsWith(CREDENTIAL_PREFIX))
                .toList();
        boolean hasUnknownProtocol = protocols.stream()
                .anyMatch(protocol -> !APPLICATION_PROTOCOLS.contains(protocol)
                        && !protocol.startsWith(CREDENTIAL_PREFIX));
        if (applicationProtocols.size() != 1 || hasUnknownProtocol || credentials.size() > 1) {
            return reject(exchange);
        }

        String applicationProtocol = applicationProtocols.getFirst();
        String existingAuthorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (credentials.isEmpty()) {
            return existingAuthorization == null
                    ? chain.filter(exchange)
                    : chain.filter(withApplicationProtocolOnly(exchange, applicationProtocol));
        }
        if (existingAuthorization != null) {
            return reject(exchange);
        }

        String credential = credentials.getFirst().substring(CREDENTIAL_PREFIX.length());
        if (credential.isBlank()
                || credential.length() > MAX_TOKEN_LENGTH
                || !COMPACT_JWT.matcher(credential).matches()) {
            return reject(exchange);
        }

        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.setBearerAuth(credential);
                    headers.set(PROTOCOL_HEADER, applicationProtocol);
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    private static boolean isBattleWebSocketHandshake(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.GET
                && "websocket".equalsIgnoreCase(
                        exchange.getRequest().getHeaders().getFirst(HttpHeaders.UPGRADE))
                && MATCH_PATH.matcher(exchange.getRequest().getPath().pathWithinApplication().value()).matches();
    }

    private static List<String> protocols(HttpHeaders headers) {
        List<String> protocols = new ArrayList<>();
        for (String value : headers.getOrEmpty(PROTOCOL_HEADER)) {
            for (String protocol : value.split(",")) {
                String trimmed = protocol.trim();
                if (!trimmed.isEmpty()) {
                    protocols.add(trimmed);
                }
            }
        }
        return List.copyOf(protocols);
    }


    private static ServerWebExchange withApplicationProtocolOnly(
            ServerWebExchange exchange, String applicationProtocol) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(PROTOCOL_HEADER, applicationProtocol))
                .build();
        return exchange.mutate().request(request).build();
    }

    private static Mono<Void> reject(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        return exchange.getResponse().setComplete();
    }
}
