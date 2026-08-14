package com.ddd.d3.battle.adapter.http;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ddd.d3.battle.BattleSecurityConfiguration;
import com.ddd.d3.battle.application.RankedMatchmakingCoordinator;
import com.ddd.d3.battle.application.RankedQueueConflictException;
import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RankedMatchmakingController.class)
@Import({BattleSecurityConfiguration.class, BattleHttpExceptionHandler.class})
class RankedMatchmakingControllerTest {

    private static final UUID PLAYER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TICKET_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID MATCH_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-14T00:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean RankedMatchmakingCoordinator matchmaking;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void d3Sec001RequiresAnAuthenticatedBattlePlayer() throws Exception {
        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .header("Idempotency-Key", TICKET_ID)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .with(jwt().jwt(token -> token.subject(PLAYER_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_identity.profile")))
                        .header("Idempotency-Key", TICKET_ID)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(matchmaking, never()).join(any(), any(), any());
    }

    @Test
    void d3Btl001TakesPlayerIdentityOnlyFromTheJwtSubject() throws Exception {
        when(matchmaking.join(TICKET_ID, PLAYER_ID, RankedMatchmaker.Language.JAVA))
                .thenReturn(new RankedMatchmakingCoordinator.JoinResult(
                        RankedMatchmakingCoordinator.Status.MATCHED, MATCH_ID, ENQUEUED_AT));

        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .with(playerJwt())
                        .header("Idempotency-Key", TICKET_ID)
                        .header("X-Correlation-Id", "corr-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.matchId").value(MATCH_ID.toString()))
                .andExpect(jsonPath("$.enqueuedAt").value(ENQUEUED_AT.toString()));

        verify(matchmaking).join(TICKET_ID, PLAYER_ID, RankedMatchmaker.Language.JAVA);
    }

    @Test
    void d3Sec001RejectsUnknownIdentityFieldsAndMalformedIdempotencyKeys() throws Exception {
        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .with(playerJwt())
                        .header("Idempotency-Key", TICKET_ID)
                        .header("X-Correlation-Id", "corr-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\",\"playerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").value("corr-unknown"));

        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .with(playerJwt())
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").value("unavailable"));

        verify(matchmaking, never()).join(any(), any(), any());
    }

    @Test
    void d3Btl001ReturnsAStableUnavailableErrorWhenTheCoordinationLeaseIsBusy() throws Exception {
        when(matchmaking.join(TICKET_ID, PLAYER_ID, RankedMatchmaker.Language.JAVA))
                .thenReturn(new RankedMatchmakingCoordinator.JoinResult(
                        RankedMatchmakingCoordinator.Status.RETRY, null, null));

        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .with(playerJwt())
                        .header("Idempotency-Key", TICKET_ID)
                        .header("X-Correlation-Id", "corr-busy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("QUEUE_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value("corr-busy"));
    }

    @Test
    void d3Sec001AllowsAuthenticatedBattlePlayersToReachTheWebSocketHandshake() throws Exception {
        mockMvc.perform(get("/ws/v1/battle/matches/" + MATCH_ID).with(playerJwt()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/ws/v1/battle/matches/" + MATCH_ID)
                        .with(jwt().jwt(token -> token.subject(PLAYER_ID.toString()))
                                .authorities(new SimpleGrantedAuthority("SCOPE_identity.profile"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void d3Btl001RejectsASecondActiveTicketForTheSamePlayer() throws Exception {
        when(matchmaking.join(TICKET_ID, PLAYER_ID, RankedMatchmaker.Language.JAVA))
                .thenThrow(new RankedQueueConflictException("player already has another active ranked ticket"));

        mockMvc.perform(post("/api/v1/battle/ranked/queue")
                        .with(playerJwt())
                        .header("Idempotency-Key", TICKET_ID)
                        .header("X-Correlation-Id", "corr-conflict")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"JAVA\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUEUE_CONFLICT"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor playerJwt() {
        return jwt().jwt(token -> token.subject(PLAYER_ID.toString()))
                .authorities(new SimpleGrantedAuthority("SCOPE_battle.play"));
    }
}
