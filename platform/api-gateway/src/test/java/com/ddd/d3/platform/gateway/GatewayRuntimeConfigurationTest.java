package com.ddd.d3.platform.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false"
        })
class GatewayRuntimeConfigurationTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String TEST_TOKEN = "header.payload.signature";

    @LocalServerPort
    int port;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void setUpJwtDecoder() {
        Jwt jwt = Jwt.withTokenValue(TEST_TOKEN)
                .header("alg", "none")
                .subject("11111111-1111-4111-8111-111111111111")
                .claim("scope", "battle.play")
                .build();
        when(jwtDecoder.decode(TEST_TOKEN)).thenReturn(Mono.just(jwt));
    }

    @Test
    void d3Qlt001UsesExplicitGatewayRoutesWithoutExposingJudge0() {
        Map<String, RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block()
                .stream()
                .collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));

        assertEquals(4, routes.size());
        assertEquals(URI.create("lb://identity-service"), routes.get("identity-api").getUri());
        assertEquals(1, routes.get("identity-api").getFilters().size());
        assertEquals("StripPrefix", routes.get("identity-api").getFilters().getFirst().getName());
        assertEquals("1", routes.get("identity-api").getFilters().getFirst().getArgs().get("_genkey_0"));
        assertEquals(URI.create("lb://battle-service"), routes.get("battle-api").getUri());
        assertEquals(URI.create("lb:ws://battle-service"), routes.get("battle-websocket").getUri());
        assertEquals(URI.create("lb://community-service"), routes.get("community-api").getUri());
        assertEquals(1, routes.get("community-api").getFilters().size());
        assertEquals("StripPrefix", routes.get("community-api").getFilters().getFirst().getName());
        assertEquals("1", routes.get("community-api").getFilters().getFirst().getArgs().get("_genkey_0"));
        assertTrue(routes.values().stream().noneMatch(route -> route.getUri().toString().contains("judge")));
    }

    @Test
    void d3Qlt001PreservesAValidCorrelationIdOnEveryIngressResponse() throws Exception {
        HttpResponse<String> response = request("trace-20260814");

        assertEquals(200, response.statusCode());
        assertEquals("trace-20260814", response.headers().firstValue(CORRELATION_HEADER).orElseThrow());
    }

    @Test
    void d3Sec001ReplacesAnInvalidCorrelationIdBeforePropagation() throws Exception {
        HttpResponse<String> response = request("invalid\r\nvalue");

        String actual = response.headers().firstValue(CORRELATION_HEADER).orElse(null);
        assertNotNull(actual);
        assertTrue(actual.matches("[0-9a-f-]{36}"));
    }

    @Test
    void d3Sec001RejectsUnauthenticatedGatewayApiTraffic() throws Exception {
        HttpResponse<String> response = request("/api/v1/auth/probe", "trace-unauthorized");

        assertEquals(401, response.statusCode());
        assertEquals("trace-unauthorized", response.headers().firstValue(CORRELATION_HEADER).orElseThrow());
    }

    @Test
    void d3Id001AllowsOnlyCanonicalAnonymousSessionEntrypoints() throws Exception {
        for (String path : new String[] {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout"
        }) {
            HttpResponse<String> response = post(path, "trace-anonymous");

            assertEquals(503, response.statusCode(), path);
        }

        assertEquals(401, request("/api/v1/users/me", "trace-profile").statusCode());
        assertEquals(401, patch("/api/v1/users/me", "trace-profile-patch").statusCode());
        assertEquals(401, post("/api/v1/auth/probe", "trace-probe").statusCode());
    }

    @Test
    void d3Stat001AllowsOnlyPublicMatchRecordReadsWithoutAuthentication() throws Exception {
        String matchId = "11111111-1111-4111-8111-111111111111";
        String playerId = "22222222-2222-4222-8222-222222222222";

        assertEquals(503, request("/api/v1/community/matches/" + matchId, "trace-match").statusCode());
        assertEquals(
                503,
                request("/api/v1/community/players/" + playerId + "/matches", "trace-player").statusCode());

        assertEquals(401, request("/api/v1/community/feed", "trace-feed").statusCode());
        assertEquals(401, post("/api/v1/community/matches/" + matchId, "trace-match-post").statusCode());
    }

    @Test
    void d3Sec001AllowsOnlyTheConfiguredBrowserPreflight() throws Exception {
        HttpResponse<String> allowed = preflight("http://localhost:5173");
        HttpResponse<String> rejected = preflight("https://untrusted.example");

        assertEquals(200, allowed.statusCode());
        assertEquals(
                "http://localhost:5173",
                allowed.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(403, rejected.statusCode());
        assertTrue(rejected.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void d3Sec001AuthenticatesBrowserWebSocketCredentialsBeforeRouting() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();

        client.get()
                .uri("/ws/v1/battle/matches/33333333-3333-4333-8333-333333333333")
                .header(HttpHeaders.CONNECTION, "Upgrade")
                .header(HttpHeaders.UPGRADE, "websocket")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
                .header("Sec-WebSocket-Protocol", "d3.battle.v2, d3.jwt." + TEST_TOKEN)
                .exchange()
                .expectStatus()
                .isEqualTo(503);
    }

    private HttpResponse<String> request(String correlationId) throws Exception {
        return request("/actuator/health", correlationId);
    }

    private HttpResponse<String> request(String path, String correlationId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        try {
            request.header(CORRELATION_HEADER, correlationId);
        } catch (IllegalArgumentException ignored) {
            request.header(CORRELATION_HEADER, "invalid value with spaces");
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header(CORRELATION_HEADER, correlationId)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(String path, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header(CORRELATION_HEADER, correlationId)
                .method("PATCH", HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/probe"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type,x-correlation-id")
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
