package com.ddd.d3.community.adapter.messaging;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "d3.community.match-finished-topic=match.finished.v1.community-test",
        "d3.community.match-finished-group=community-match-finished-integration-test"
})
@Testcontainers
class CommunityMatchFinishedKafkaIntegrationTest {

    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2@sha256:"
            + "5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e";
    private static final String TOPIC = "match.finished.v1.community-test";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    @Autowired DataSource dataSource;
    @Autowired Environment environment;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void d3Stat001StartsNewConsumerGroupsFromTheEarliestRetainedEvent() {
        assertEquals("earliest", environment.getProperty("spring.kafka.consumer.auto-offset-reset"));
    }

    @Test
    void d3Stat001ConsumesKafkaReplayIntoOneCommunityProjection() throws Exception {
        String payload = """
                {
                  "eventId":"11111111-1111-4111-8111-111111111111",
                  "eventType":"match.finished",
                  "version":1,
                  "occurredAt":"2026-08-16T01:59:00Z",
                  "correlationId":"44444444-4444-4444-8444-444444444444",
                  "aggregateId":"22222222-2222-4222-8222-222222222222",
                  "aggregateVersion":7,
                  "data":{
                    "matchId":"22222222-2222-4222-8222-222222222222",
                    "result":"PLAYER_ONE_WIN",
                    "ranked":true,
                    "playerIds":[
                      "33333333-3333-4333-8333-333333333331",
                      "33333333-3333-4333-8333-333333333332"
                    ]
                  }
                }
                """;

        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, "22222222-2222-4222-8222-222222222222", payload)).get();
            producer.send(new ProducerRecord<>(TOPIC, "22222222-2222-4222-8222-222222222222", payload)).get();
        }

        JdbcClient jdbc = JdbcClient.create(dataSource);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertEquals(1L, jdbc.sql("select count(*) from inbox_event").query(Long.class).single());
            assertEquals(1L, jdbc.sql("select count(*) from match_projection").query(Long.class).single());
            assertEquals("ACTIVE", jdbc.sql("""
                            select projection_status from match_projection
                            where match_id = '22222222-2222-4222-8222-222222222222'
                            """).query(String.class).single());
            assertTrue(jdbc.sql("""
                            select player_records is null
                            from match_projection
                            where match_id = '22222222-2222-4222-8222-222222222222'
                            """).query(Boolean.class).single());
        });
    }
}
