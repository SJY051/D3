package com.ddd.d3.battle.adapter.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ddd.d3.battle.application.BattleMatchView;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BattleSnapshotMessageV1Test {

    private static final UUID MATCH_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAYER_ONE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLAYER_TWO = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void d3Btl002SerializesTheClosedParticipantScopedSnapshotContract() throws Exception {
        BattleMatchView view = new BattleMatchView(
                MATCH_ID,
                4,
                NOW,
                BattleMatchView.State.RUNNING,
                NOW,
                NOW.plusSeconds(600),
                new BattleMatchView.Participant(
                        PLAYER_ONE, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Participant(
                        PLAYER_TWO, true, BattleMatchView.ConnectionState.DISCONNECTED, NOW.plusSeconds(30)),
                null);

        JsonNode message = objectMapper.readTree(
                objectMapper.writeValueAsString(BattleSnapshotMessageV1.from(view)));

        assertEquals("MATCH_SNAPSHOT", message.path("type").asText());
        assertEquals(1, message.path("version").asInt());
        assertEquals(4, message.path("sequence").asLong());
        assertEquals(NOW.toString(), message.path("serverTime").asText());
        assertEquals("DISCONNECTED", message.path("payload").path("opponent").path("connectionState").asText());
        assertEquals(
                NOW.plusSeconds(30).toString(),
                message.path("payload").path("opponent").path("reconnectDeadline").asText());
        assertTrue(message.path("payload").has("result"));
        assertTrue(message.path("payload").path("result").isNull());
        assertFalse(message.path("payload").path("self").has("activeConnectionGeneration"));
        assertFalse(message.path("payload").path("opponent").has("completedConnectionGeneration"));
    }

    @Test
    void d3Sec001NeverSerializesThePrivateIncidentReference() throws Exception {
        BattleMatchView view = new BattleMatchView(
                MATCH_ID,
                5,
                NOW,
                BattleMatchView.State.FINISHED,
                NOW.minusSeconds(60),
                NOW.plusSeconds(540),
                new BattleMatchView.Participant(
                        PLAYER_ONE, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Participant(
                        PLAYER_TWO, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Result(
                        BattleMatchView.Outcome.VOIDED,
                        null,
                        BattleMatchView.ResolutionReason.PLATFORM_INCIDENT,
                        NOW));

        JsonNode message = objectMapper.readTree(
                objectMapper.writeValueAsString(BattleSnapshotMessageV1.from(view)));

        assertEquals("VOIDED", message.path("payload").path("result").path("outcome").asText());
        assertTrue(message.path("payload").path("result").path("winnerId").isNull());
        assertFalse(message.path("payload").path("result").has("incidentReference"));
    }

    @Test
    void d3Qlt001SerializesAnImportedLegacyDrawWithoutInventingAWinner() throws Exception {
        BattleMatchView view = new BattleMatchView(
                MATCH_ID,
                2,
                NOW,
                BattleMatchView.State.FINISHED,
                NOW.minusSeconds(60),
                NOW.plusSeconds(540),
                new BattleMatchView.Participant(
                        PLAYER_ONE, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Participant(
                        PLAYER_TWO, true, BattleMatchView.ConnectionState.CONNECTED, null),
                new BattleMatchView.Result(
                        BattleMatchView.Outcome.DRAW,
                        null,
                        BattleMatchView.ResolutionReason.LEGACY_IMPORT,
                        NOW));

        JsonNode result = objectMapper.readTree(
                        objectMapper.writeValueAsString(BattleSnapshotMessageV1.from(view)))
                .path("payload")
                .path("result");

        assertEquals("DRAW", result.path("outcome").asText());
        assertTrue(result.path("winnerId").isNull());
        assertEquals("LEGACY_IMPORT", result.path("reason").asText());
    }
}
