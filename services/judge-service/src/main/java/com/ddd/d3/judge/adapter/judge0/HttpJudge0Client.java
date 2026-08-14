package com.ddd.d3.judge.adapter.judge0;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class HttpJudge0Client implements Judge0Client {

    private static final int MAX_PROVIDER_RESPONSE_BYTES = 1_048_576;
    private static final ExecutorService BODY_READER =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("judge0-body-reader-", 0).factory());

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Judge0HttpSettings settings;
    private final String baseUrl;

    public HttpJudge0Client(HttpClient httpClient, ObjectMapper objectMapper, Judge0HttpSettings settings) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.settings = Objects.requireNonNull(settings, "settings");
        String value = settings.baseUri().toString();
        this.baseUrl = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public Set<Integer> availableLanguageIds() {
        JsonNode response = send(request("GET", "/languages", null), 200);
        if (!response.isArray()) {
            throw new Judge0ClientException("Judge0 returned an invalid language response");
        }
        Set<Integer> languageIds = new LinkedHashSet<>();
        for (JsonNode language : response) {
            int languageId = language.path("id").asInt(-1);
            if (languageId <= 0 || !languageIds.add(languageId)) {
                throw new Judge0ClientException("Judge0 returned an invalid language response");
            }
        }
        return Set.copyOf(languageIds);
    }

    @Override
    public Judge0Result execute(Judge0Request request) {
        Objects.requireNonNull(request, "request");
        JsonNode accepted = send(
                request(
                        "POST",
                        "/submissions?base64_encoded=false&wait=false",
                        serialize(providerPayload(request))),
                201);
        String token = accepted.path("token").asText();
        try {
            UUID.fromString(token);
        } catch (IllegalArgumentException exception) {
            throw new Judge0ClientException("Judge0 returned an invalid submission token");
        }

        long deadline = System.nanoTime() + settings.pollTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = send(
                    request(
                            "GET",
                            "/submissions/" + token
                                    + "?base64_encoded=false&fields=status%2Ctime%2Cmemory",
                            null),
                    200);
            String status = response.path("status").path("description").asText();
            if (!("In Queue".equals(status) || "Processing".equals(status))) {
                if (status.isBlank()) {
                    throw new Judge0ClientException("Judge0 returned an invalid submission response");
                }
                long cpuTimeMicros = "Accepted".equals(status)
                        ? requiredSecondsToMicros(response.path("time"))
                        : 0;
                return new Judge0Result(
                        status,
                        cpuTimeMicros,
                        response.path("memory").asLong(0),
                        "judge0-language-" + request.languageId());
            }
            sleep(settings.pollInterval());
        }
        throw new Judge0ClientException("Judge0 submission polling exceeded its deadline");
    }

    private Map<String, Object> providerPayload(Judge0Request request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_code", request.sourceCode());
        payload.put("language_id", request.languageId());
        payload.put("stdin", request.stdin());
        payload.put("expected_output", request.expectedOutput());
        payload.put("cpu_time_limit", request.cpuTimeLimitSeconds());
        payload.put("wall_time_limit", request.wallTimeLimitSeconds());
        payload.put("memory_limit", request.memoryLimitKib());
        payload.put("stack_limit", request.stackLimitKib());
        payload.put("max_processes_and_or_threads", request.processLimit());
        payload.put("max_file_size", request.fileSizeLimitKib());
        payload.put("enable_network", request.networkEnabled());
        return payload;
    }

    private HttpRequest request(String method, String pathAndQuery, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + pathAndQuery))
                .timeout(settings.requestTimeout())
                .header(settings.authenticationHeader(), settings.authenticationToken());
        if (body == null) {
            return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        }
        return builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private JsonNode send(HttpRequest request, int expectedStatus) {
        long deadline = System.nanoTime() + settings.requestTimeout().toNanos();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new Judge0ClientException("Judge0 response body exceeded its deadline");
                }
                Future<byte[]> bodyRead = BODY_READER.submit(() ->
                        input.readNBytes(MAX_PROVIDER_RESPONSE_BYTES + 1));
                try {
                    bytes = bodyRead.get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (TimeoutException exception) {
                    bodyRead.cancel(true);
                    throw new Judge0ClientException("Judge0 response body exceeded its deadline", exception);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof IOException ioException) {
                        throw ioException;
                    }
                    throw new Judge0ClientException("Judge0 response body could not be read", cause);
                }
            }
            if (bytes.length > MAX_PROVIDER_RESPONSE_BYTES) {
                throw new Judge0ClientException("Judge0 response exceeded its size limit");
            }
            if (response.statusCode() != expectedStatus) {
                throw new Judge0ClientException("Judge0 returned HTTP " + response.statusCode());
            }
            return objectMapper.readTree(bytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Judge0ClientException("Judge0 request was interrupted", exception);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof Judge0ClientException clientException) {
                throw clientException;
            }
            throw new Judge0ClientException("Judge0 request failed", exception);
        }
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException exception) {
            throw new Judge0ClientException("Judge0 request could not be encoded", exception);
        }
    }

    private static long requiredSecondsToMicros(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            throw new Judge0ClientException("Judge0 omitted execution time for an accepted submission");
        }
        try {
            long micros = new BigDecimal(value.asText()).movePointRight(6).longValueExact();
            if (micros < 0) {
                throw new Judge0ClientException("Judge0 returned a negative execution time");
            }
            return micros;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new Judge0ClientException("Judge0 returned an invalid execution time");
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Judge0ClientException("Judge0 polling was interrupted", exception);
        }
    }
}
