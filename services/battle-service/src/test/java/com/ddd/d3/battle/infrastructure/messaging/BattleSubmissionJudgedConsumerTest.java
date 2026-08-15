package com.ddd.d3.battle.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ddd.d3.battle.application.BattleJudgeReferenceStore;
import com.ddd.d3.battle.application.BattleJudgedSubmissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class BattleSubmissionJudgedConsumerTest {

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-15T01:00:00Z");

    @Test
    void d3Btl001AcceptsVersionedSafeJudgeEventWithoutSource() {
        BattleJudgedSubmissionService service = mock(BattleJudgedSubmissionService.class);
        when(service.receive(any())).thenReturn(true);
        BattleSubmissionJudgedConsumer consumer = new BattleSubmissionJudgedConsumer(
                service, JsonMapper.builder().build(), Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        consumer.receive("""
                {
                  "eventId":"11111111-1111-1111-1111-111111111111",
                  "eventType":"submission.judged",
                  "version":1,
                  "occurredAt":"2026-08-15T00:59:00Z",
                  "correlationId":"22222222-2222-2222-2222-222222222222",
                  "aggregateId":"33333333-3333-3333-3333-333333333333",
                  "aggregateVersion":1,
                  "data":{
                    "submissionId":"33333333-3333-3333-3333-333333333333",
                    "status":"ACCEPTED",
                    "language":"JAVA",
                    "evidenceVersion":"judge-evidence-v1"
                  }
                }
                """);

        ArgumentCaptor<BattleJudgeReferenceStore.JudgedEvent> event =
                ArgumentCaptor.forClass(BattleJudgeReferenceStore.JudgedEvent.class);
        verify(service).receive(event.capture());
        assertEquals(UUID.fromString("33333333-3333-3333-3333-333333333333"), event.getValue().submissionId());
        assertEquals(RECEIVED_AT, event.getValue().receivedAt());
    }

    @Test
    void d3Btl001RejectsUnknownPrivateEventFields() {
        BattleSubmissionJudgedConsumer consumer = new BattleSubmissionJudgedConsumer(
                mock(BattleJudgedSubmissionService.class),
                JsonMapper.builder().build(),
                Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

        String payload = """
                {
                  "eventId":"11111111-1111-1111-1111-111111111111",
                  "eventType":"submission.judged",
                  "version":1,
                  "occurredAt":"2026-08-15T00:59:00Z",
                  "correlationId":"22222222-2222-2222-2222-222222222222",
                  "aggregateId":"33333333-3333-3333-3333-333333333333",
                  "aggregateVersion":1,
                  "data":{
                    "submissionId":"33333333-3333-3333-3333-333333333333",
                    "status":"ACCEPTED",
                    "language":"JAVA",
                    "evidenceVersion":"judge-evidence-v1",
                    "sourceCode":"must-not-cross-event-boundary"
                  }
                }
                """;

        assertThrows(IllegalArgumentException.class, () -> consumer.receive(payload));
    }
}
