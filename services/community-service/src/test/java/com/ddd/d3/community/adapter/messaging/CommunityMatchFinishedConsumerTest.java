package com.ddd.d3.community.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ddd.d3.community.application.MatchFinishedProjectionService;
import com.ddd.d3.community.application.MatchFinishedProjectionService.MatchFinishedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class CommunityMatchFinishedConsumerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-16T02:00:00Z");

    @Test
    void d3Stat001ConsumesOnlyTheVersionedSeatOrderedMatchContract() {
        MatchFinishedProjectionService service = mock(MatchFinishedProjectionService.class);
        when(service.receive(any())).thenReturn(true);
        CommunityMatchFinishedConsumer consumer = consumer(service);

        consumer.receive(validPayload());

        ArgumentCaptor<MatchFinishedEvent> event = ArgumentCaptor.forClass(MatchFinishedEvent.class);
        verify(service).receive(event.capture());
        assertEquals(UUID.fromString("11111111-1111-4111-8111-111111111111"), event.getValue().eventId());
        assertEquals(UUID.fromString("22222222-2222-4222-8222-222222222222"), event.getValue().aggregateId());
        assertEquals(7, event.getValue().aggregateVersion());
        assertEquals("PLAYER_ONE_WIN", event.getValue().result());
        assertEquals(List.of(
                UUID.fromString("33333333-3333-4333-8333-333333333331"),
                UUID.fromString("33333333-3333-4333-8333-333333333332")), event.getValue().playerIds());
        assertEquals(RECEIVED_AT, event.getValue().receivedAt());
    }

    @Test
    void d3Sec001RejectsUnknownPrivateEventFields() {
        MatchFinishedProjectionService service = mock(MatchFinishedProjectionService.class);
        CommunityMatchFinishedConsumer consumer = consumer(service);
        String payload = validPayload().replace(
                "\"playerIds\":[",
                "\"sourceCode\":\"must-not-cross-event-boundary\",\"playerIds\":[");

        assertThrows(IllegalArgumentException.class, () -> consumer.receive(payload));
        verifyNoInteractions(service);
    }

    @Test
    void d3Stat001RejectsAnAggregateThatDoesNotMatchThePayloadMatch() {
        MatchFinishedProjectionService service = mock(MatchFinishedProjectionService.class);
        CommunityMatchFinishedConsumer consumer = consumer(service);
        String payload = validPayload().replace(
                "\"aggregateId\":\"22222222-2222-4222-8222-222222222222\"",
                "\"aggregateId\":\"99999999-9999-4999-8999-999999999999\"");

        assertThrows(IllegalArgumentException.class, () -> consumer.receive(payload));
        verifyNoInteractions(service);
    }

    @Test
    void d3Sec001RejectsDuplicateEnvelopeFields() {
        MatchFinishedProjectionService service = mock(MatchFinishedProjectionService.class);
        CommunityMatchFinishedConsumer consumer = consumer(service);
        String payload = validPayload().replace(
                "\"version\":1,",
                "\"version\":1,\"version\":1,");

        assertThrows(IllegalArgumentException.class, () -> consumer.receive(payload));
        verifyNoInteractions(service);
    }

    @Test
    void d3Stat001RejectsMissingRequiredScalarFields() {
        MatchFinishedProjectionService service = mock(MatchFinishedProjectionService.class);
        CommunityMatchFinishedConsumer consumer = consumer(service);

        for (String field : List.of("\"version\":1,", "\"aggregateVersion\":7,", "\"ranked\":true,")) {
            String payload = validPayload().replace(field, "");
            assertThrows(IllegalArgumentException.class, () -> consumer.receive(payload));
        }
        verifyNoInteractions(service);
    }

    private CommunityMatchFinishedConsumer consumer(MatchFinishedProjectionService service) {
        return new CommunityMatchFinishedConsumer(
                service,
                JsonMapper.builder().build(),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));
    }

    private String validPayload() {
        return """
                {
                  "eventId":"11111111-1111-4111-8111-111111111111",
                  "eventType":"match.finished",
                  "version":1,
                  "occurredAt":"2026-08-16T01:59:00Z",
                  "correlationId":"44444444-4444-4444-8444-444444444444",
                  "aggregateId":"22222222-2222-4222-8222-222222222222",
                  "aggregateVersion":7,
                  "data":{
                    "matchId":"22222222-2222-4222-8222-222222222222",
                    "result":"PLAYER_ONE_WIN",
                    "ranked":true,
                    "playerIds":[
                      "33333333-3333-4333-8333-333333333331",
                      "33333333-3333-4333-8333-333333333332"
                    ]
                  }
                }
                """;
    }
}
