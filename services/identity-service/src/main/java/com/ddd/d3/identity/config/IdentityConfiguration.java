package com.ddd.d3.identity.config;

import com.ddd.d3.identity.adapter.persistence.JdbcIdentityRepository;
import com.ddd.d3.identity.application.IdentityRepository;
import com.ddd.d3.identity.application.IdentityService;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class IdentityConfiguration {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    IdentityRepository identityRepository(JdbcClient jdbcClient) {
        return new JdbcIdentityRepository(jdbcClient);
    }

    @Bean
    IdentityService identityService(IdentityRepository repository, PasswordEncoder passwordEncoder) {
        return new IdentityService(
                repository, passwordEncoder, Clock.systemUTC(), UUID::randomUUID, IdentityConfiguration::randomToken);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
