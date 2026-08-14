package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.BattleMatchNotFoundException;
import com.ddd.d3.battle.application.BattleMatchViewService;
import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class BattleWebSocketHandshakeInterceptorTest {

    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void d3Sec001AuthorizesOnlyACommittedMatchParticipant() {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ID)).thenReturn(mock(com.ddd.d3.battle.application.BattleMatchView.class));
        BattleWebSocketHandshakeInterceptor interceptor = new BattleWebSocketHandshakeInterceptor(views);
        ServerHttpRequest request = request(PLAYER_ID.toString());
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                request, mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);

        assertTrue(accepted);
        assertEquals(MATCH_ID, attributes.get(BattleWebSocketHandshakeInterceptor.MATCH_ID_ATTRIBUTE));
        assertEquals(PLAYER_ID, attributes.get(BattleWebSocketHandshakeInterceptor.VIEWER_ID_ATTRIBUTE));
    }

    @Test
    void d3Sec001HidesARealMatchFromAnOutsider() {
        BattleMatchViewService views = mock(BattleMatchViewService.class);
        when(views.read(MATCH_ID, PLAYER_ID)).thenThrow(new BattleMatchNotFoundException());
        BattleWebSocketHandshakeInterceptor interceptor = new BattleWebSocketHandshakeInterceptor(views);
        ServerHttpResponse response = mock(ServerHttpResponse.class);

        boolean accepted = interceptor.beforeHandshake(
                request(PLAYER_ID.toString()), response, mock(WebSocketHandler.class), new HashMap<>());

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.NOT_FOUND);
    }

    private static ServerHttpRequest request(String principalName) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn(principalName);
        when(request.getPrincipal()).thenReturn(principal);
        when(request.getURI()).thenReturn(URI.create(
                "http://localhost/ws/v1/battle/matches/" + MATCH_ID));
        return request;
    }
}
