package com.ddd.d3.community.adapter.messaging;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        "d3.community.user-profile-changed-topic=user-profile.changed.v1.community-test",
        "d3.community.user-profile-changed-group=community-user-profile-changed-integration-test"
})
@Testcontainers
class CommunityUserProfileChangedKafkaIntegrationTest {

    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2@sha256:"
            + "5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e";
    private static final String TOPIC = "user-profile.changed.v1.community-test";
    private static final String USER = "22222222-2222-4222-8222-222222222222";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    @Autowired DataSource dataSource;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Test
    void d3Stat001ConsumesKafkaReplayIntoOneIdentityProjection() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);

        String payload = """
                {
                  "eventId":"11111111-1111-4111-8111-111111111111",
                  "eventType":"user-profile.changed",
                  "version":1,
                  "occurredAt":"2026-08-16T01:59:00Z",
                  "correlationId":"%s",
                  "aggregateId":"%s",
                  "aggregateVersion":7,
                  "data":{
                    "userId":"%s",
                    "handle":"alice",
                    "profileVersion":7
                  }
                }
                """.formatted(USER, USER, USER);

        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>(TOPIC, USER, payload)).get();
            producer.send(new ProducerRecord<>(TOPIC, USER, payload)).get();
        }

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertEquals(1L, jdbc.sql("select count(*) from inbox_event").query(Long.class).single());
            assertEquals("alice|7", jdbc.sql("""
                            select handle || '|' || identity_source_version
                            from profile_projection where user_id = cast(:userId as uuid)
                            """).param("userId", USER).query(String.class).single());
        });
    }
}
