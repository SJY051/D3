package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RankedMatchmakingCoordinator {

    public enum Status {
        QUEUED,
        MATCHED,
        RETRY
    }

    public record JoinResult(Status status, UUID matchId, Instant enqueuedAt) {}

    private final RankedMatchmaker matchmaker;
    private final RankedQueueStore queue;
    private final RankedMatchStore matches;
    private final PublicRatingReader ratings;
    private final Clock clock;
    private final Duration entryTtl;
    private final Duration leaseTtl;

    public RankedMatchmakingCoordinator(
            RankedMatchmaker matchmaker,
            RankedQueueStore queue,
            RankedMatchStore matches,
            PublicRatingReader ratings,
            Clock clock,
            Duration entryTtl,
            Duration leaseTtl) {
        this.matchmaker = Objects.requireNonNull(matchmaker, "matchmaker must not be null");
        this.queue = Objects.requireNonNull(queue, "queue must not be null");
        this.matches = Objects.requireNonNull(matches, "matches must not be null");
        this.ratings = Objects.requireNonNull(ratings, "ratings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.entryTtl = requirePositive(entryTtl, "entryTtl");
        this.leaseTtl = requirePositive(leaseTtl, "leaseTtl");
    }

    public JoinResult join(UUID ticketId, UUID playerId, RankedMatchmaker.Language language) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        Objects.requireNonNull(language, "language must not be null");

        Optional<UUID> committedMatch = matches.findMatchIdByTicket(ticketId, playerId);
        if (committedMatch.isPresent()) {
            return new JoinResult(Status.MATCHED, committedMatch.orElseThrow(), null);
        }
        Optional<UUID> activeMatch = matches.findActiveMatchIdByPlayer(playerId);
        if (activeMatch.isPresent()) {
            throw new RankedQueueConflictException("player already belongs to an active ranked match");
        }

        Instant now = clock.instant();
        RankedQueueStore.Ticket ticket = new RankedQueueStore.Ticket(
                ticketId, playerId, language, ratings.publicRating(playerId), now);
        Optional<RankedQueueStore.Lease> acquired = queue.tryAcquire(language, leaseTtl);
        if (acquired.isEmpty()) {
            return new JoinResult(Status.RETRY, null, null);
        }

        try (RankedQueueStore.Lease lease = acquired.orElseThrow()) {
            RankedMatchmaker.Entry joined = lease.enqueue(ticket, entryTtl);
            List<RankedMatchmaker.Pair> pairs = matchmaker.pair(lease.activeEntries(), now);
            UUID joinedMatchId = null;
            for (RankedMatchmaker.Pair pair : pairs) {
                try {
                    RankedMatchStore.RankedMatch match = matches.create(pair, now);
                    lease.remove(List.of(pair.playerOne(), pair.playerTwo()));
                    if (contains(pair, playerId)) {
                        joinedMatchId = match.matchId();
                    }
                } catch (ActiveRankedMatchConflictException conflict) {
                    RankedMatchmaker.Entry staleEntry = entryFor(pair, conflict.playerId());
                    lease.remove(List.of(staleEntry));
                    if (conflict.playerId().equals(playerId)) {
                        throw new RankedQueueConflictException("player already belongs to an active ranked match");
                    }
                }
            }
            return joinedMatchId == null
                    ? new JoinResult(Status.QUEUED, null, joined.enqueuedAt())
                    : new JoinResult(Status.MATCHED, joinedMatchId, joined.enqueuedAt());
        }
    }

    private static boolean contains(RankedMatchmaker.Pair pair, UUID playerId) {
        return pair.playerOne().playerId().equals(playerId)
                || pair.playerTwo().playerId().equals(playerId);
    }

    private static RankedMatchmaker.Entry entryFor(RankedMatchmaker.Pair pair, UUID playerId) {
        if (pair.playerOne().playerId().equals(playerId)) {
            return pair.playerOne();
        }
        if (pair.playerTwo().playerId().equals(playerId)) {
            return pair.playerTwo();
        }
        throw new IllegalStateException("Active ranked match conflict did not name a paired player");
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
