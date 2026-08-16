package com.ddd.d3.community.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.community.adapter.persistence.JdbcCommunityRepository.MatchFinishedProjection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

class MatchFinishedListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static String envelope(String aggregateVersion, String data, String extra) {
        return """
                {
                  "eventId": "55555555-5555-4555-8555-555555555551",
                  "eventType": "match.finished",
                  "version": 1,
                  "occurredAt": "2026-08-16T00:00:00Z",
                  "correlationId": "c-1",
                  "aggregateId": "44444444-4444-4444-8444-444444444441",
                  "aggregateVersion": %s,
                  %s"data": %s
                }
                """.formatted(aggregateVersion, extra, data);
    }

    private static final String TWO_PLAYERS = """
            {
              "matchId": "44444444-4444-4444-8444-444444444441",
              "result": "PLAYER_ONE_WIN",
              "ranked": true,
              "playerIds": [
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222"
              ]
            }
            """;

    @Test
    void d3Stat001MapsSeatOrderedEnvelopeToProjection() {
        MatchFinishedProjection event = MatchFinishedListener.parse(envelope("7", TWO_PLAYERS, ""), objectMapper);

        assertEquals(UUID.fromString("55555555-5555-4555-8555-555555555551"), event.eventId());
        assertEquals(7L, event.aggregateVersion());
        assertEquals(UUID.fromString("44444444-4444-4444-8444-444444444441"), event.matchId());
        assertEquals("PLAYER_ONE_WIN", event.result());
        assertEquals(UUID.fromString("11111111-1111-4111-8111-111111111111"), event.playerOne());
        assertEquals(UUID.fromString("22222222-2222-4222-8222-222222222222"), event.playerTwo());
    }

    @Test
    void d3Stat001RejectsAWrongEventTypeOnTheMatchFinishedTopic() {
        String wrong = envelope("7", TWO_PLAYERS, "").replace("\"match.finished\"", "\"rating.changed\"");
        assertThrows(IllegalArgumentException.class, () -> MatchFinishedListener.parse(wrong, objectMapper));
    }

    @Test
    void d3Stat001RejectsAnUnsupportedContractVersion() {
        String v2 = envelope("7", TWO_PLAYERS, "").replace("\"version\": 1", "\"version\": 2");
        assertThrows(IllegalArgumentException.class, () -> MatchFinishedListener.parse(v2, objectMapper));
    }

    @Test
    void d3Stat001RejectsAMalformedAggregateVersionInsteadOfCoercingIt() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MatchFinishedListener.parse(envelope("\"not-a-number\"", TWO_PLAYERS, ""), objectMapper));
    }

    @Test
    void d3Stat001RejectsUnknownEnvelopeProperties() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MatchFinishedListener.parse(envelope("7", TWO_PLAYERS, "\"unexpected\": true,\n  "), objectMapper));
    }

    @Test
    void d3Stat001RejectsAnAggregateIdThatDoesNotMatchTheProjectedMatch() {
        String mismatched = envelope("7", TWO_PLAYERS, "").replace(
                "\"aggregateId\": \"44444444-4444-4444-8444-444444444441\"",
                "\"aggregateId\": \"99999999-9999-4999-8999-999999999999\"");
        assertThrows(IllegalArgumentException.class, () -> MatchFinishedListener.parse(mismatched, objectMapper));
    }

    @Test
    void d3Stat001RejectsAMissingRankedFlagInsteadOfDefaultingToFalse() {
        String noRanked = """
                {
                  "matchId": "44444444-4444-4444-8444-444444444441",
                  "result": "PLAYER_ONE_WIN",
                  "playerIds": [
                    "11111111-1111-4111-8111-111111111111",
                    "22222222-2222-4222-8222-222222222222"
                  ]
                }
                """;
        assertThrows(
                IllegalArgumentException.class,
                () -> MatchFinishedListener.parse(envelope("7", noRanked, ""), objectMapper));
    }

    @Test
    void d3Stat001RejectsANullSeatInThePlayerList() {
        String nullSeat = """
                {
                  "matchId": "44444444-4444-4444-8444-444444444441",
                  "result": "PLAYER_ONE_WIN",
                  "ranked": true,
                  "playerIds": [null, "22222222-2222-4222-8222-222222222222"]
                }
                """;
        assertThrows(
                IllegalArgumentException.class,
                () -> MatchFinishedListener.parse(envelope("7", nullSeat, ""), objectMapper));
    }

    @Test
    void d3Stat001RejectsAPayloadWithoutTwoSeatOrderedPlayers() {
        String onePlayer = """
                {
                  "matchId": "44444444-4444-4444-8444-444444444441",
                  "result": "PLAYER_ONE_WIN",
                  "ranked": true,
                  "playerIds": ["11111111-1111-4111-8111-111111111111"]
                }
                """;
        assertThrows(
                IllegalArgumentException.class,
                () -> MatchFinishedListener.parse(envelope("7", onePlayer, ""), objectMapper));
    }
}
