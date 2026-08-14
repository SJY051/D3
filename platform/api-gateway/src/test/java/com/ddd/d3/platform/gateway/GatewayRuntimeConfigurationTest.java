package com.ddd.d3.platform.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.config.import=",
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false"
        })
class GatewayRuntimeConfigurationTest {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @LocalServerPort
    int port;

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void d3Qlt001UsesExplicitGatewayRoutesWithoutExposingJudge0() {
        Map<String, RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block()
                .stream()
                .collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));

        assertEquals(4, routes.size());
        assertEquals(URI.create("lb://identity-service"), routes.get("identity-api").getUri());
        assertEquals(URI.create("lb://battle-service"), routes.get("battle-api").getUri());
        assertEquals(URI.create("lb:ws://battle-service"), routes.get("battle-websocket").getUri());
        assertEquals(URI.create("lb://community-service"), routes.get("community-api").getUri());
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
