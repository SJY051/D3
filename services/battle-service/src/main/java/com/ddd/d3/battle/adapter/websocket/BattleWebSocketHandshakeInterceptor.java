package com.ddd.d3.battle.adapter.websocket;

import com.ddd.d3.battle.application.BattleMatchNotFoundException;
import com.ddd.d3.battle.application.BattleMatchViewService;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
final class BattleWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    static final String MATCH_ID_ATTRIBUTE = "d3.matchId";
    static final String VIEWER_ID_ATTRIBUTE = "d3.viewerId";
    private static final Pattern MATCH_PATH = Pattern.compile(
            "^/ws/v1/battle/matches/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})$");
    private final BattleMatchViewService views;

    BattleWebSocketHandshakeInterceptor(BattleMatchViewService views) {
        this.views = Objects.requireNonNull(views, "views must not be null");
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (request.getPrincipal() == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Matcher path = MATCH_PATH.matcher(request.getURI().getPath());
        if (!path.matches()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        UUID matchId;
        UUID viewerId;
        try {
            matchId = UUID.fromString(path.group(1));
            viewerId = UUID.fromString(request.getPrincipal().getName());
        } catch (IllegalArgumentException | NullPointerException exception) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            views.read(matchId, viewerId);
            attributes.put(MATCH_ID_ATTRIBUTE, matchId);
            attributes.put(VIEWER_ID_ATTRIBUTE, viewerId);
            return true;
        } catch (BattleMatchNotFoundException exception) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {}
}
