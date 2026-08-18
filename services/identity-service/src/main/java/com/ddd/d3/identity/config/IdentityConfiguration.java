package com.ddd.d3.identity.config;

import com.ddd.d3.identity.adapter.messaging.IdentityOutboxPublisher;
import com.ddd.d3.identity.adapter.persistence.JdbcIdentityOutboxStore;
import com.ddd.d3.identity.adapter.persistence.JdbcIdentityRepository;
import com.ddd.d3.identity.application.DemoUserSeeder;
import com.ddd.d3.identity.application.DemoUserSeeder.DemoUser;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.application.IdentityService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableScheduling
public class IdentityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdentityConfiguration.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("demo-a@d3.dev", "demo-a", "Demo Player A"),
            new DemoUser("demo-b@d3.dev", "demo-b", "Demo Player B"));

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    TransactionTemplate identityTransactionTemplate(DataSource dataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    IdentityRepository identityRepository(
            JdbcClient jdbcClient, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        return new JdbcIdentityRepository(jdbcClient, transactionTemplate, objectMapper);
    }

    @Bean
    JdbcIdentityOutboxStore identityOutboxStore(DataSource dataSource) {
        return new JdbcIdentityOutboxStore(dataSource);
    }

    @Bean
    IdentityOutboxPublisher identityOutboxPublisher(
            JdbcIdentityOutboxStore outboxStore,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${d3.identity.user-profile-changed-topic:user-profile.changed.v1}") String topic) {
        return new IdentityOutboxPublisher(outboxStore, kafkaTemplate, topic, Clock.systemUTC());
    }

    @Bean
    IdentityService identityService(IdentityRepository repository, PasswordEncoder passwordEncoder) {
        return new IdentityService(
                repository, passwordEncoder, Clock.systemUTC(), UUID::randomUUID, IdentityConfiguration::randomToken);
    }

    @Bean
    ApplicationRunner demoUserSeeder(
            IdentityService identityService,
            @Value("${D3_SEED_DEMO_PASSWORD:}") String demoPassword) {
        DemoUserSeeder seeder = new DemoUserSeeder(identityService, DEMO_USERS, demoPassword);
        return args -> {
            int created = seeder.seed();
            if (created > 0) {
                log.info("seeded {} demo account(s)", created);
            }
        };
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
