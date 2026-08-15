package com.ddd.d3.platform.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

class BattleWebSocketCredentialWebFilterTest {

    private static final String MATCH_URL =
            "http://localhost/ws/v1/battle/matches/33333333-3333-4333-8333-333333333333";
    private static final String PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    private static final String TOKEN = "header.payload.signature";

    private final BattleWebSocketCredentialWebFilter filter = new BattleWebSocketCredentialWebFilter();

    @Test
    void d3Sec001ConvertsAndRemovesTheBrowserCredentialProtocolForEveryApplicationVersion() {
        for (String applicationProtocol : new String[] {
            BattleWebSocketCredentialWebFilter.APPLICATION_PROTOCOL,
            BattleWebSocketCredentialWebFilter.V3_APPLICATION_PROTOCOL
        }) {
            MockServerWebExchange exchange = handshake(
                    applicationProtocol + ", " + BattleWebSocketCredentialWebFilter.CREDENTIAL_PREFIX + TOKEN);
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

            filter.filter(exchange, candidate -> {
                        forwarded.set(candidate);
                        return candidate.getResponse().setComplete();
                    })
                    .block();

            HttpHeaders headers = forwarded.get().getRequest().getHeaders();
            assertEquals("Bearer " + TOKEN, headers.getFirst(HttpHeaders.AUTHORIZATION));
            assertEquals(applicationProtocol, headers.getFirst(PROTOCOL_HEADER));
            assertFalse(headers.getFirst(PROTOCOL_HEADER).contains(TOKEN));
        }
    }

    @Test
    void d3Sec001RejectsAmbiguousOrMalformedWebSocketCredentials() {
        for (MockServerWebExchange exchange : new MockServerWebExchange[] {
            handshake(BattleWebSocketCredentialWebFilter.CREDENTIAL_PREFIX + TOKEN),
            handshake(BattleWebSocketCredentialWebFilter.APPLICATION_PROTOCOL + ", unknown"),
            handshake(BattleWebSocketCredentialWebFilter.APPLICATION_PROTOCOL + ", "
                    + BattleWebSocketCredentialWebFilter.V3_APPLICATION_PROTOCOL),
            handshake(BattleWebSocketCredentialWebFilter.APPLICATION_PROTOCOL + ", d3.jwt.not-a-jwt"),
            handshake(BattleWebSocketCredentialWebFilter.APPLICATION_PROTOCOL + ", d3.jwt." + TOKEN
                    + ", d3.jwt.other.payload.signature"),
            handshakeWithAuthorization(
                    BattleWebSocketCredentialWebFilter.APPLICATION_PROTOCOL + ", d3.jwt." + TOKEN)
        }) {
            AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

            filter.filter(exchange, candidate -> {
                        forwarded.set(candidate);
                        return candidate.getResponse().setComplete();
                    })
                    .block();

            assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
            assertNull(forwarded.get());
        }
    }

    @Test
    void d3Sec001LeavesNonWebSocketTrafficUntouched() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(
                        "http://localhost/api/v1/battle/ranked/queue")
                .header(PROTOCOL_HEADER, "d3.jwt." + TOKEN)
                .build());
        AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();

        filter.filter(exchange, candidate -> {
                    forwarded.set(candidate);
                    return candidate.getResponse().setComplete();
                })
                .block();

        assertSame(exchange, forwarded.get());
        assertNull(forwarded.get().getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
    }

    private static MockServerWebExchange handshake(String protocols) {
        return MockServerWebExchange.from(handshakeRequest(protocols));
    }

    private static MockServerWebExchange handshakeWithAuthorization(String protocols) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(MATCH_URL)
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(PROTOCOL_HEADER, protocols)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .build());
    }

    private static MockServerHttpRequest handshakeRequest(String protocols) {
        return MockServerHttpRequest.get(MATCH_URL)
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(PROTOCOL_HEADER, protocols)
                .build();
    }
}
