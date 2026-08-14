package com.ddd.d3.battle.adapter.http;

import com.ddd.d3.battle.application.RankedMatchmakingCoordinator;
import com.ddd.d3.battle.application.RankedQueueBusyException;
import com.ddd.d3.battle.domain.RankedMatchmaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/battle/ranked/queue")
public final class RankedMatchmakingController {

    private final RankedMatchmakingCoordinator matchmaking;

    public RankedMatchmakingController(RankedMatchmakingCoordinator matchmaking) {
        this.matchmaking = matchmaking;
    }

    @PostMapping
    public ResponseEntity<JoinResponse> join(
            @RequestHeader("Idempotency-Key") UUID ticketId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody JoinRequest request) {
        UUID playerId = UUID.fromString(jwt.getSubject());
        RankedMatchmakingCoordinator.JoinResult result =
                matchmaking.join(ticketId, playerId, request.language());
        if (result.status() == RankedMatchmakingCoordinator.Status.RETRY) {
            throw new RankedQueueBusyException();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new JoinResponse(result.status(), result.matchId(), result.enqueuedAt()));
    }

    public record JoinRequest(@NotNull RankedMatchmaker.Language language) {}

    public record JoinResponse(
            RankedMatchmakingCoordinator.Status status,
            UUID matchId,
            Instant enqueuedAt) {}
}
