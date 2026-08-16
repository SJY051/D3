package com.ddd.d3.community.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ddd.d3.community.adapter.persistence.JdbcProfileRatingStore;
import com.ddd.d3.community.adapter.persistence.JdbcProfileRatingStore.RatingChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class CommunityRatingChangedConsumerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-16T02:00:00Z");
    private static final UUID USER = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private CommunityRatingChangedConsumer consumer(JdbcProfileRatingStore store) {
        return new CommunityRatingChangedConsumer(
                store, JsonMapper.builder().build(), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    private static String validPayload() {
        return """
                {
                  "eventId":"11111111-1111-4111-8111-111111111111",
                  "eventType":"rating.changed",
                  "version":1,
                  "occurredAt":"2026-08-16T01:59:00Z",
                  "correlationId":"44444444-4444-4444-8444-444444444441",
                  "aggregateId":"22222222-2222-4222-8222-222222222222",
                  "aggregateVersion":7,
                  "data":{
                    "userId":"22222222-2222-4222-8222-222222222222",
                    "matchId":"44444444-4444-4444-8444-444444444441",
                    "ratingBefore":1420,
                    "ratingAfter":1450,
                    "seasonRpAfter":60,
                    "tierAfter":"GOLD"
                  }
                }
                """;
    }

    @Test
    void d3Stat001ProjectsTheVersionedRatingForTheMatchingUser() {
        JdbcProfileRatingStore store = mock(JdbcProfileRatingStore.class);
        when(store.apply(any())).thenReturn(true);

        consumer(store).receive(validPayload());

        ArgumentCaptor<RatingChangedEvent> event = ArgumentCaptor.forClass(RatingChangedEvent.class);
        verify(store).apply(event.capture());
        assertEquals(USER, event.getValue().userId());
        assertEquals(USER, event.getValue().aggregateId());
        assertEquals(7L, event.getValue().aggregateVersion());
        assertEquals(1450, event.getValue().ratingAfter());
        assertEquals(60, event.getValue().seasonRpAfter());
        assertEquals("GOLD", event.getValue().tierAfter());
        assertEquals(RECEIVED_AT, event.getValue().receivedAt());
    }

    @Test
    void d3Sec001RejectsUnknownPrivateEventFields() {
        JdbcProfileRatingStore store = mock(JdbcProfileRatingStore.class);
        String payload = validPayload().replace(
                "\"tierAfter\":\"GOLD\"",
                "\"tierAfter\":\"GOLD\",\"sourceCode\":\"must-not-cross-event-boundary\"");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Sec001RejectsAnUnsupportedContractVersion() {
        JdbcProfileRatingStore store = mock(JdbcProfileRatingStore.class);
        String payload = validPayload().replace("\"version\":1", "\"version\":2");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsAnAggregateThatDoesNotMatchTheRatedUser() {
        JdbcProfileRatingStore store = mock(JdbcProfileRatingStore.class);
        String payload = validPayload().replace(
                "\"aggregateId\":\"22222222-2222-4222-8222-222222222222\"",
                "\"aggregateId\":\"99999999-9999-4999-8999-999999999999\"");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsAMissingRatingInsteadOfCoercingIt() {
        JdbcProfileRatingStore store = mock(JdbcProfileRatingStore.class);
        String payload = validPayload().replace("\"ratingAfter\":1450,", "");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }

    @Test
    void d3Stat001RejectsANegativeSeasonRp() {
        JdbcProfileRatingStore store = mock(JdbcProfileRatingStore.class);
        String payload = validPayload().replace("\"seasonRpAfter\":60", "\"seasonRpAfter\":-1");
        assertThrows(IllegalArgumentException.class, () -> consumer(store).receive(payload));
        verifyNoInteractions(store);
    }
}
