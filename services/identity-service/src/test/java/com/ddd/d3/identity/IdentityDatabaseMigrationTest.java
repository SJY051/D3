package com.ddd.d3.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class IdentityDatabaseMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine");

    @Test
    void d3Qlt001MigratesTheIdentityOwnedSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        int migrations = Flyway.configure().dataSource(dataSource).load().migrate().migrationsExecuted;
        Set<String> tables = Set.copyOf(JdbcClient.create(dataSource)
                .sql("select table_name from information_schema.tables where table_schema = 'public'")
                .query(String.class)
                .list());

        assertEquals(1, migrations);
        assertEquals(Set.of("flyway_schema_history", "user_account", "login_identity", "refresh_session", "outbox_event"), tables);
    }
}
