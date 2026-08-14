package com.ddd.d3.judge.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.judge.adapter.fake.DeterministicFakeJudgeAdapter;
import java.time.Clock;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.mock.env.MockEnvironment;

class JudgeServiceConfigurationTest {

    private final JudgeServiceConfiguration configuration = new JudgeServiceConfiguration();

    @Test
    void d3Sec001AllowsTheFakeAdapterOnlyInAnExplicitNonProductionProfile() {
        MockEnvironment local = new MockEnvironment().withProperty("spring.profiles.active", "local");
        local.setActiveProfiles("local");
        assertInstanceOf(
                DeterministicFakeJudgeAdapter.class,
                configuration.deterministicFakeJudgeExecutionAdapter(Clock.systemUTC(), local));

        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("production");
        assertThrows(
                IllegalStateException.class,
                () -> configuration.deterministicFakeJudgeExecutionAdapter(Clock.systemUTC(), production));
    }

    @Test
    void d3Jdg001ConfiguresStringSerializersForTheApplicationKafkaProducer() throws IOException {
        var properties = new YamlPropertySourceLoader()
                .load("judge-application", new ClassPathResource("application.yml"))
                .getFirst();

        assertEquals(
                "org.apache.kafka.common.serialization.StringSerializer",
                properties.getProperty("spring.kafka.producer.key-serializer"));
        assertEquals(
                "org.apache.kafka.common.serialization.StringSerializer",
                properties.getProperty("spring.kafka.producer.value-serializer"));
    }
}
