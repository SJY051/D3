package com.ddd.d3.identity.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ddd.d3.identity.application.DemoUserSeeder.DemoUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class DemoUserSeederTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneOffset.UTC);
    private static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("demo-a@d3.dev", "demo-a", "Demo Player A"),
            new DemoUser("demo-b@d3.dev", "demo-b", "Demo Player B"));

    private InMemoryIdentityRepository repository;
    private IdentityService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryIdentityRepository();
        AtomicInteger sequence = new AtomicInteger();
        service = new IdentityService(
                repository,
                new BCryptPasswordEncoder(),
                CLOCK,
                () -> new UUID(0, sequence.incrementAndGet()),
                () -> "raw-token-" + sequence.incrementAndGet());
    }

    @Test
    void d3Ux002SeedsTheDemoUsersAndLetsThemLogIn() {
        int created = new DemoUserSeeder(service, DEMO_USERS, "rehearsal-secret").seed();

        assertEquals(2, created);
        assertEquals(2, repository.accountCount());
        assertDoesNotThrow(() -> service.login("demo-a@d3.dev", "rehearsal-secret"));
        assertDoesNotThrow(() -> service.login("demo-b@d3.dev", "rehearsal-secret"));
    }

    @Test
    void d3Sec001SeedsNothingWithoutASuppliedPassword() {
        assertEquals(0, new DemoUserSeeder(service, DEMO_USERS, "").seed());
        assertEquals(0, new DemoUserSeeder(service, DEMO_USERS, null).seed());
        assertEquals(0, repository.accountCount());
    }

    @Test
    void d3Ux002IsIdempotentAcrossRestarts() {
        DemoUserSeeder seeder = new DemoUserSeeder(service, DEMO_USERS, "rehearsal-secret");
        seeder.seed();

        int createdOnSecondRun = seeder.seed();

        assertEquals(0, createdOnSecondRun);
        assertEquals(2, repository.accountCount());
    }
}
