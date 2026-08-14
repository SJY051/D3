package com.ddd.d3.platform.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VersionedNativeConfigurationTest {

    @LocalServerPort
    int port;

    @Test
    void d3Qlt001ServesTheVersionedLocalRuntimeConfiguration() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/application/local"))
                .GET()
                .build();

        HttpResponse<String> response =
                HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("d3.runtime.config-version"));
        assertTrue(response.body().contains("local-v1"));
        assertTrue(response.body().contains("eureka.instance.hostname"));
        assertTrue(response.body().contains("eureka.instance.ip-address"));
        assertTrue(response.body().contains("127.0.0.1"));
    }
}
