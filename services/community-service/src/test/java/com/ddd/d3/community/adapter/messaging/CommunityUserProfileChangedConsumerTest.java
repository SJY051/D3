package com.ddd.d3.community.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ddd.d3.community.adapter.persistence.JdbcProfileIdentityStore;
import com.ddd.d3.community.adapter.persistence.JdbcProfileIdentityStore.UserProfileChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class CommunityUserProfileChangedConsumerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-16T02:00:00Z");
    private static final UUID USER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private CommunityUserProfileChangedConsumer consumer(JdbcProfileIdentityStore store) {
        return new CommunityUserProfileChangedConsumer(
                store, JsonMapper.builder().build(), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    private static String validPayload() {
        return validPayload(7);
    }

    private static String validPayload(long profileVersion) {
        return """
                {
                  "eventId":"11111111-1111-4111-8111-111111111111",
                  "eventType":"user-profile.changed",
                  "version":1,
                  "occurredAt":"2026-08-16T01:59:00Z",
                  "correlationId":"22222222-2222-4222-8222-222222222222",
                  "aggregateId":"22222222-2222-4222-8222-222222222222",
                  "aggregateVersion":%d,
                  "data":{
                    "userId":"22222222-2222-4222-8222-222222222222",
                    "handle":"alice",
                    "profileVersion":%d
                  }
                }
                """.formatted(profileVersion, profileVersion);
    }

    @Test
    void d3Stat001ProjectsTheVersionedHandleForTheMatchingUser() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        when(store.apply(any())).thenReturn(true);

        consumer(store).receive(validPayload());

        ArgumentCaptor<UserProfileChangedEvent> event = ArgumentCaptor.forClass(UserProfileChangedEvent.class);
        verify(store).apply(event.capture());
        assertEquals(USER, event.getValue().userId());
        assertEquals(USER, event.getValue().aggregateId());
        assertEquals(7L, event.getValue().aggregateVersion());
        assertEquals("alice", event.getValue().handle());
        assertEquals(RECEIVED_AT, event.getValue().receivedAt());
    }

    @Test
    void d3Stat001AcceptsMatchingProfileVersionsOutsideTheLongCache() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        when(store.apply(any())).thenReturn(true);

        consumer(store).receive(validPayload(128));

        ArgumentCaptor<UserProfileChangedEvent> event = ArgumentCaptor.forClass(UserProfileChangedEvent.class);
        verify(store).apply(event.capture());
        assertEquals(128L, event.getValue().aggregateVersion());
    }

    @Test
    void d3Sec001RejectsUnknownPrivateEventFields() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        String payload = validPayload().replace(
                "\"handle\":\"alice\"",
                "\"handle\":\"alice\",\"email\":\"must-not-cross-event-boundary@d3.dev\"");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Sec001RejectsAnUnsupportedContractVersion() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        String payload = validPayload().replace("\"version\":1", "\"version\":2");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsAnAggregateThatDoesNotMatchTheProfileUser() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        String payload = validPayload().replace(
                "\"aggregateId\":\"22222222-2222-4222-8222-222222222222\"",
                "\"aggregateId\":\"99999999-9999-4999-8999-999999999999\"");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsAnAggregateVersionThatDisagreesWithProfileVersion() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        String payload = validPayload().replace("\"profileVersion\":7", "\"profileVersion\":6");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsAMissingHandleInsteadOfCoercingIt() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        String payload = validPayload().replace("\"handle\":\"alice\",", "");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsABlankHandle() {
        JdbcProfileIdentityStore store = mock(JdbcProfileIdentityStore.class);
        String payload = validPayload().replace("\"handle\":\"alice\"", "\"handle\":\"   \"");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }
}
