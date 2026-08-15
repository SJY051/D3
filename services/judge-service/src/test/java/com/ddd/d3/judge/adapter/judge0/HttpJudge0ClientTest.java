package com.ddd.d3.judge.adapter.judge0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HttpJudge0ClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<JsonNode> submissionRequests = new ArrayList<>();
    private final AtomicInteger polls = new AtomicInteger();
    private HttpServer server;
    private HttpJudge0Client client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/languages", exchange -> respond(exchange, 200, "[{\"id\":71},{\"id\":50}]"));
        server.createContext("/submissions", this::handleSubmission);
        server.start();
        client = client(Duration.ofSeconds(1));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void d3Jdg001DiscoversAuthenticatedRuntimeIdsAndPollsAQueuedSubmission() {
        assertEquals(java.util.Set.of(50, 71), client.availableLanguageIds());

        Judge0Result result = client.execute(request());

        assertEquals("Accepted", result.statusDescription());
        assertEquals(12_500, result.cpuTimeMicros());
        assertEquals(2048, result.memoryKib());
        assertEquals(2, polls.get());
        assertEquals(1, submissionRequests.size());
        JsonNode payload = submissionRequests.getFirst();
        assertEquals(71, payload.path("language_id").asInt());
        assertEquals("private source", payload.path("source_code").asText());
        assertEquals(2, payload.path("cpu_time_limit").asInt());
        assertEquals(5, payload.path("wall_time_limit").asInt());
        assertEquals(262_144, payload.path("memory_limit").asInt());
        assertEquals(65_536, payload.path("stack_limit").asInt());
        assertEquals(60, payload.path("max_processes_and_or_threads").asInt());
        assertEquals(1_024, payload.path("max_file_size").asInt());
        assertFalse(payload.path("enable_network").asBoolean());
    }

    @Test
    void d3Jdg001FailsClosedOnNonSuccessAndBoundedPolling() {
        server.removeContext("/languages");
        server.createContext("/languages", exchange -> respond(exchange, 503, "private upstream body"));
        Judge0ClientException discoveryFailure = assertThrows(
                Judge0ClientException.class, client::availableLanguageIds);
        assertFalse(discoveryFailure.getMessage().contains("private upstream body"));

        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
            } else {
                respond(exchange, 200, "{\"status\":{\"description\":\"Processing\"}}");
            }
        });
        HttpJudge0Client bounded = client(Duration.ofMillis(25));
        assertThrows(Judge0ClientException.class, () -> bounded.execute(request()));
    }

    @Test
    void d3Sec001BoundsAProviderThatStallsAfterSendingResponseHeaders() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        server.removeContext("/languages");
        server.createContext("/languages", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            headersSent.countDown();
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        HttpJudge0Client bounded = new HttpJudge0Client(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                objectMapper,
                new Judge0HttpSettings(
                        baseUri,
                        baseUri,
                        "X-Auth-Token",
                        "test-token",
                        Duration.ofMillis(100),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1)));

        assertThrows(Judge0ClientException.class, bounded::availableLanguageIds);
        org.junit.jupiter.api.Assertions.assertTrue(headersSent.await(1, TimeUnit.SECONDS));
    }

    @Test
    void d3Jdg001FailsClosedWhenAnAcceptedProviderResultOmitsRuntimeEvidence() {
        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
            } else {
                respond(exchange, 200, "{\"status\":{\"description\":\"Accepted\"},\"memory\":2048}");
            }
        });

        assertThrows(Judge0ClientException.class, () -> client.execute(request()));
    }

    @Test
    void d3Jdg001RoundsHighPrecisionProviderTimeToMicroseconds() {
        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"status\":{\"description\":\"Accepted\"},\"time\":\"0.059333333333333\",\"memory\":2048}");
            }
        });

        Judge0Result result = client.execute(request());

        assertEquals(59_333, result.cpuTimeMicros());
    }

    @Test
    void d3Jdg001RejectsNegativeHighPrecisionProviderTime() {
        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
            } else {
                respond(
                        exchange,
                        200,
                        "{\"status\":{\"description\":\"Accepted\"},\"time\":\"-0.0000004\",\"memory\":2048}");
            }
        });

        assertThrows(Judge0ClientException.class, () -> client.execute(request()));
    }

    @Test
    void d3Jdg001RetriesPollingWithTheSameTokenWithoutSubmittingAgain() {
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
                return;
            }
            if (gets.incrementAndGet() == 1) {
                respond(exchange, 503, "temporary outage");
                return;
            }
            assertEquals(
                    "/submissions/11111111-1111-4111-8111-111111111111",
                    exchange.getRequestURI().getPath());
            respond(exchange, 200, "{\"status\":{\"description\":\"Accepted\"},\"time\":\"0.0125\",\"memory\":2048}");
        });

        Judge0Result result = client.execute(request());

        assertEquals("Accepted", result.statusDescription());
        assertEquals(1, posts.get());
        assertEquals(2, gets.get());
    }

    @Test
    void d3Jdg001BoundsPollingRetriesWithoutRepeatingThePost() {
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
                return;
            }
            gets.incrementAndGet();
            respond(exchange, 503, "temporary outage");
        });

        assertThrows(Judge0ClientException.class, () -> client.execute(request()));
        assertEquals(1, posts.get());
        assertEquals(3, gets.get());
    }

    @Test
    void d3Jdg001NeverRepeatsAPostWhenItsResponseIsLost() {
        AtomicInteger posts = new AtomicInteger();
        server.removeContext("/submissions");
        server.createContext("/submissions", exchange -> {
            posts.incrementAndGet();
            exchange.close();
        });

        assertThrows(Judge0ClientException.class, () -> client.execute(request()));
        assertEquals(1, posts.get());
    }

    private HttpJudge0Client client(Duration pollTimeout) {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpJudge0Client(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                objectMapper,
                new Judge0HttpSettings(
                        baseUri,
                        baseUri,
                        "X-Auth-Token",
                        "test-token",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        pollTimeout));
    }

    @Test
    void d3Sec001RejectsAProviderOriginOutsideTheExplicitAllowlist() {
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        assertThrows(
                IllegalArgumentException.class,
                () -> new Judge0HttpSettings(
                        baseUri,
                        URI.create("https://judge0.internal"),
                        "X-Auth-Token",
                        "test-token",
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1),
                        Duration.ofSeconds(1)));
    }

    private void handleSubmission(HttpExchange exchange) throws IOException {
        assertEquals("test-token", exchange.getRequestHeaders().getFirst("X-Auth-Token"));
        if ("POST".equals(exchange.getRequestMethod())) {
            submissionRequests.add(objectMapper.readTree(exchange.getRequestBody()));
            assertEquals("base64_encoded=false&wait=false", exchange.getRequestURI().getRawQuery());
            respond(exchange, 201, "{\"token\":\"11111111-1111-4111-8111-111111111111\"}");
            return;
        }
        assertEquals(
                "base64_encoded=false&fields=status%2Ctime%2Cmemory",
                exchange.getRequestURI().getRawQuery());
        if (polls.incrementAndGet() == 1) {
            respond(exchange, 200, "{\"status\":{\"description\":\"Processing\"}}");
        } else {
            respond(exchange, 200, "{\"status\":{\"description\":\"Accepted\"},\"time\":\"0.0125\",\"memory\":2048}");
        }
    }

    private static Judge0Request request() {
        return new Judge0Request(
                71,
                "private source",
                "input",
                "output",
                2,
                5,
                262_144,
                65_536,
                60,
                1_024,
                false);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
