package com.ddd.d3.judge.config;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.judge.adapter.fake.DeterministicFakeJudgeAdapter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
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
}
