package com.ddd.d3.platform.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
class GatewayIdentitySessionFlowTest {

    private static final HttpServer IDENTITY = startIdentityStub();

    @LocalServerPort
    int port;

    @MockitoBean
    ReactiveJwtDecoder jwtDecoder;

    @DynamicPropertySource
    static void gatewayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.server.webflux.routes[0].id", () -> "identity-api");
        registry.add(
                "spring.cloud.gateway.server.webflux.routes[0].uri",
                () -> "http://127.0.0.1:" + IDENTITY.getAddress().getPort());
        registry.add(
                "spring.cloud.gateway.server.webflux.routes[0].predicates[0]",
                () -> "Path=/api/v1/auth/**,/api/v1/users/**");
        registry.add("spring.cloud.gateway.server.webflux.routes[0].filters[0]", () -> "StripPrefix=1");
    }

    @BeforeEach
    void setUpJwtDecoder() {
        Jwt jwt = Jwt.withTokenValue("access-2")
                .header("alg", "none")
                .subject("00000000-0000-4000-8000-000000000001")
                .build();
        when(jwtDecoder.decode("access-2")).thenReturn(Mono.just(jwt));
    }

    @Test
    void d3Id001RunsTheSessionFlowThroughTheGateway() {
        WebTestClient webTestClient = WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build();

        webTestClient.post()
                .uri("/api/v1/auth/register")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userId").isEqualTo("00000000-0000-4000-8000-000000000001");

        String firstRefresh = webTestClient.post()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("access-1")
                .returnResult()
                .getResponseCookies()
                .getFirst("D3_REFRESH_TOKEN")
                .getValue();

        String secondRefresh = webTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("D3_REFRESH_TOKEN", firstRefresh)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.accessToken").isEqualTo("access-2")
                .returnResult()
                .getResponseCookies()
                .getFirst("D3_REFRESH_TOKEN")
                .getValue();

        assertEquals("refresh-2", secondRefresh);

        webTestClient.get()
                .uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth("access-2"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.handle").isEqualTo("dev");
    }

    private static HttpServer startIdentityStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", GatewayIdentitySessionFlowTest::handleIdentity);
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("could not start identity stub", exception);
        }
    }

    private static void handleIdentity(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("POST".equals(method) && "/v1/auth/register".equals(path)) {
            json(exchange, 201, "{\"userId\":\"00000000-0000-4000-8000-000000000001\"}");
        } else if ("POST".equals(method) && "/v1/auth/login".equals(path)) {
            token(exchange, "access-1", "refresh-1");
        } else if ("POST".equals(method) && "/v1/auth/refresh".equals(path)) {
            assertEquals("D3_REFRESH_TOKEN=refresh-1", exchange.getRequestHeaders().getFirst("Cookie"));
            token(exchange, "access-2", "refresh-2");
        } else if ("GET".equals(method) && "/v1/users/me".equals(path)) {
            json(exchange, 200, """
                    {"userId":"00000000-0000-4000-8000-000000000001","handle":"dev","email":"dev@d3.dev","displayName":"Dev"}
                    """);
        } else {
            json(exchange, 404, "{}");
        }
    }

    private static void token(HttpExchange exchange, String accessToken, String refreshToken) throws IOException {
        exchange.getResponseHeaders()
                .add("Set-Cookie", "D3_REFRESH_TOKEN=" + refreshToken + "; Path=/api/v1/auth; HttpOnly; SameSite=Lax");
        json(exchange, 200, "{\"userId\":\"00000000-0000-4000-8000-000000000001\",\"accessToken\":\""
                + accessToken + "\"}");
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
