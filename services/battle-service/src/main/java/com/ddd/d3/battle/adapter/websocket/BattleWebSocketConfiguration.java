package com.ddd.d3.battle.adapter.websocket;

import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class BattleWebSocketConfiguration implements WebSocketConfigurer {

    static final String MATCH_PATH = "/ws/v1/battle/matches/*";
    private final BattleWebSocketHandler handler;
    private final BattleWebSocketHandshakeInterceptor handshakeInterceptor;
    private final String webOrigin;

    BattleWebSocketConfiguration(
            BattleWebSocketHandler handler,
            BattleWebSocketHandshakeInterceptor handshakeInterceptor,
            @Value("${d3.web-origin:http://localhost:5173}") String webOrigin) {
        this.handler = Objects.requireNonNull(handler, "handler must not be null");
        this.handshakeInterceptor = Objects.requireNonNull(
                handshakeInterceptor, "handshakeInterceptor must not be null");
        this.webOrigin = requireOrigin(webOrigin);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, MATCH_PATH)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(webOrigin);
    }

    private static String requireOrigin(String origin) {
        Objects.requireNonNull(origin, "webOrigin must not be null");
        URI uri;
        try {
            uri = URI.create(origin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("webOrigin must be one exact HTTP(S) origin", exception);
        }
        boolean supportedScheme = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        if (!supportedScheme
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty())
                || uri.getQuery() != null
                || uri.getFragment() != null
                || origin.contains("*")) {
            throw new IllegalArgumentException("webOrigin must be one exact HTTP(S) origin");
        }
        return origin;
    }
}
