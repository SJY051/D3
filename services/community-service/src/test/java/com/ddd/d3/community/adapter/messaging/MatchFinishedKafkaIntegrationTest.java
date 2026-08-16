package com.ddd.d3.community.adapter.messaging;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class MatchFinishedKafkaIntegrationTest {

    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2@sha256:"
            + "5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e";
    private static final String TOPIC = "match.finished.v1";

    private static final UUID MATCH_ID = UUID.fromString("44444444-4444-4444-8444-444444444441");
    private static final UUID PLAYER_ONE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_TWO = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired DataSource dataSource;

    @Test
    void d3Stat001ConsumesMatchFinishedFromKafkaAndProjectsTheActiveRecord() throws Exception {
        // The listener subscription auto-creates the topic; the record is read via earliest offset.
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        try {
            new KafkaTemplate<>(producerFactory).send(TOPIC, MATCH_ID.toString(), payload()).get();

            JdbcClient jdbc = JdbcClient.create(dataSource);
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertEquals(
                    "PLAYER_ONE_WIN|ACTIVE",
                    jdbc.sql("""
                            select result || '|' || projection_status
                            from match_projection where match_id = :id
                            """)
                            .param("id", MATCH_ID)
                            .query(String.class)
                            .optional()
                            .orElse("absent")));
        } finally {
            producerFactory.destroy();
        }
    }

    private static String payload() {
        return """
                {
                  "eventId": "55555555-5555-4555-8555-555555555551",
                  "eventType": "match.finished",
                  "version": 1,
                  "occurredAt": "2026-08-16T00:00:00Z",
                  "correlationId": "c-1",
                  "aggregateId": "%s",
                  "aggregateVersion": 1,
                  "data": {
                    "matchId": "%s",
                    "result": "PLAYER_ONE_WIN",
                    "ranked": true,
                    "playerIds": ["%s", "%s"]
                  }
                }
                """.formatted(MATCH_ID, MATCH_ID, PLAYER_ONE, PLAYER_TWO);
    }
}
