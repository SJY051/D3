package com.ddd.d3.identity.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddd.d3.identity.adapter.persistence.JdbcIdentityOutboxStore;
import com.ddd.d3.identity.adapter.persistence.JdbcIdentityRepository;
import com.ddd.d3.identity.domain.Account;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class IdentityOutboxPublisherTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private static final String TOPIC = "user-profile.changed.v1";

    private JdbcClient jdbc;
    private JdbcIdentityRepository repository;
    private JdbcIdentityOutboxStore store;

    @BeforeEach
    void migrateAndReset() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new JdbcIdentityRepository(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
        store = new JdbcIdentityOutboxStore(dataSource);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesPendingRowKeyedByUserThenMarksItPublished() {
        Account account = new Account(
                UUID.randomUUID(), "dev", "dev@d3.dev", "hash", "Dev", Account.ACTIVE, CLOCK.instant());
        repository.saveAccount(account);

        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(eq(TOPIC), eq(account.id().toString()), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        IdentityOutboxPublisher publisher = new IdentityOutboxPublisher(store, kafka, TOPIC, CLOCK);

        int published = publisher.dispatchBatch();

        assertEquals(1, published);
        verify(kafka).send(eq(TOPIC), eq(account.id().toString()), org.mockito.ArgumentMatchers.anyString());
        // The row is now published, so a second pass sends nothing.
        assertEquals(0, publisher.dispatchBatch());
        assertPublished(account.id());
    }

    private void assertPublished(UUID aggregateId) {
        Timestamp publishedAt = jdbc.sql(
                        "select published_at from outbox_event where aggregate_id = :id")
                .param("id", aggregateId)
                .query(Timestamp.class)
                .single();
        org.junit.jupiter.api.Assertions.assertNotNull(publishedAt);
    }
}
