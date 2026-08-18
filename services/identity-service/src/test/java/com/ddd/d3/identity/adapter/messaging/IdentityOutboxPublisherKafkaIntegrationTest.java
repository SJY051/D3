package com.ddd.d3.identity.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ddd.d3.identity.application.IdentityService;
import com.ddd.d3.identity.adapter.persistence.JdbcIdentityOutboxStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
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
        "spring.profiles.active=test",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "D3_BATTLE_SERVICE_CLIENT_SECRET=test-secret",
        "d3.identity.user-profile-changed-topic=user-profile.changed.v1.identity-test",
        "d3.identity.outbox-delay=60s"
})
@Testcontainers
class IdentityOutboxPublisherKafkaIntegrationTest {

    private static final String KAFKA_IMAGE = "apache/kafka:4.1.2@sha256:"
            + "5cc2a2fd93fa2687b44015eee04fb2c3edd9e526bd64bf8bec5ff1e268772e0e";
    private static final String TOPIC = "user-profile.changed.v1.identity-test";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse(KAFKA_IMAGE).asCompatibleSubstituteFor("apache/kafka"));

    @Autowired DataSource dataSource;
    @Autowired IdentityService identityService;
    @Autowired JdbcIdentityOutboxStore store;
    @Autowired IdentityOutboxPublisher publisher;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void resetDatabase() {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void d3Stat001PublishesProfileChangedThroughTheRealKafkaTemplate() throws Exception {
        createTopic();

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "identity-profile-publisher-test");
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (var consumer = new KafkaConsumer<String, String>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            consumer.poll(Duration.ofMillis(250));

            var userId = identityService.register("dev@d3.dev", "dev", "Dev", "correct horse battery staple");
            assertEquals(1, publisher.dispatchBatch());

            var records = consumer.poll(Duration.ofSeconds(10));
            assertEquals(1, records.count());
            var record = records.iterator().next();
            assertEquals(userId.toString(), record.key());
            assertEquals(userId.toString(), field(record.value(), "aggregateId"));
            assertEquals("user-profile.changed", field(record.value(), "eventType"));
            assertEquals("dev", dataField(record.value(), "handle"));
            assertEquals("0", dataField(record.value(), "profileVersion"));
        }
    }

    @Test
    void d3Stat001SweepsPostMigrationOldWriterAccountsBeforePublishing() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        var userId = java.util.UUID.randomUUID();
        jdbc.sql("""
                        insert into user_account (
                            id, handle, email, display_name, status, created_at, updated_at
                        ) values (:id, 'old-writer', 'old-writer@d3.dev', 'Old Writer', 'ACTIVE', now(), now())
                        """)
                .param("id", userId)
                .update();

        assertEquals(0L, jdbc.sql("select count(*) from outbox_event where aggregate_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single());

        assertEquals(1, store.backfillMissingProfileEvents());

        assertEquals(1L, jdbc.sql("""
                        select count(*)
                        from outbox_event
                        where aggregate_id = :id
                          and aggregate_version = 0
                          and event_type = 'user-profile.changed'
                        """)
                .param("id", userId)
                .query(Long.class)
                .single());
    }

    private static void createTopic() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get();
        } catch (ExecutionException exception) {
            if (!(exception.getCause() instanceof TopicExistsException)) {
                throw exception;
            }
        }
    }

    private static String field(String payload, String field) {
        return Json.read(payload, "$." + field);
    }

    private static String dataField(String payload, String field) {
        return Json.read(payload, "$.data." + field);
    }

    private static final class Json {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static String read(String payload, String pointer) {
            try {
                String path = pointer.substring(2).replace(".", "/");
                var node = MAPPER.readTree(payload).at("/" + path);
                return node.isTextual() ? node.asString() : node.toString();
            } catch (tools.jackson.core.JacksonException exception) {
                throw new IllegalArgumentException(exception);
            }
        }
    }
}
