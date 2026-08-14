package com.ddd.d3.battle.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RankedMatchmakingCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID PLAYER_ONE = new UUID(1, 1);
    private static final UUID PLAYER_TWO = new UUID(1, 2);
    private static final UUID TICKET_ONE = new UUID(2, 1);
    private static final UUID TICKET_TWO = new UUID(2, 2);
    private static final UUID TICKET_THREE = new UUID(2, 3);

    @Test
    void d3Btl001CommitsAndRemovesOnePairThenReplaysTheTicketFromPostgres() {
        FakeQueue queue = new FakeQueue();
        FakeMatches matches = new FakeMatches();
        RankedMatchmakingCoordinator coordinator = coordinator(queue, matches);

        RankedMatchmakingCoordinator.JoinResult queued =
                coordinator.join(TICKET_ONE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);
        RankedMatchmakingCoordinator.JoinResult matched =
                coordinator.join(TICKET_TWO, PLAYER_TWO, RankedMatchmaker.Language.JAVA);
        RankedMatchmakingCoordinator.JoinResult replayed =
                coordinator.join(TICKET_TWO, PLAYER_TWO, RankedMatchmaker.Language.JAVA);

        assertEquals(RankedMatchmakingCoordinator.Status.QUEUED, queued.status());
        assertEquals(NOW, queued.enqueuedAt());
        assertEquals(RankedMatchmakingCoordinator.Status.MATCHED, matched.status());
        assertEquals(matched.matchId(), replayed.matchId());
        assertEquals(1, matches.created);
        assertEquals(List.of(), queue.entries);
    }

    @Test
    void d3Btl001ReturnsTheActiveMatchForANewTicketFromAnAlreadyMatchedPlayer() {
        FakeQueue queue = new FakeQueue();
        FakeMatches matches = new FakeMatches();
        RankedMatchmakingCoordinator coordinator = coordinator(queue, matches);
        coordinator.join(TICKET_ONE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);
        RankedMatchmakingCoordinator.JoinResult matched =
                coordinator.join(TICKET_TWO, PLAYER_TWO, RankedMatchmaker.Language.JAVA);

        RankedMatchmakingCoordinator.JoinResult rejoined =
                coordinator.join(TICKET_THREE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);

        assertEquals(RankedMatchmakingCoordinator.Status.MATCHED, rejoined.status());
        assertEquals(matched.matchId(), rejoined.matchId());
        assertEquals(List.of(), queue.entries);
    }

    @Test
    void d3Btl001KeepsAnUnmatchedOpponentQueuedWhenAStalePlayerConflictsAtCommit() {
        FakeQueue queue = new FakeQueue();
        FakeMatches matches = new FakeMatches();
        RankedMatchmakingCoordinator coordinator = coordinator(queue, matches);
        coordinator.join(TICKET_ONE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);
        matches.conflictPlayerId = PLAYER_ONE;
        matches.conflictMatchId = new UUID(3, 99);

        RankedMatchmakingCoordinator.JoinResult result =
                coordinator.join(TICKET_TWO, PLAYER_TWO, RankedMatchmaker.Language.JAVA);

        assertEquals(RankedMatchmakingCoordinator.Status.QUEUED, result.status());
        assertEquals(List.of(PLAYER_TWO), queue.entries.stream().map(RankedMatchmaker.Entry::playerId).toList());
    }

    @Test
    void d3Btl001ReturnsRetryWithoutMutatingTheQueueWhenTheLeaseIsBusy() {
        FakeQueue queue = new FakeQueue();
        queue.available = false;
        RankedMatchmakingCoordinator coordinator = coordinator(queue, new FakeMatches());

        RankedMatchmakingCoordinator.JoinResult result =
                coordinator.join(TICKET_ONE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);

        assertEquals(RankedMatchmakingCoordinator.Status.RETRY, result.status());
        assertEquals(List.of(), queue.entries);
    }

    @Test
    void d3Sec001DoesNotReplayAnotherPlayersMatchingTicketValue() {
        FakeQueue queue = new FakeQueue();
        FakeMatches matches = new FakeMatches();
        RankedMatchmakingCoordinator coordinator = coordinator(queue, matches);
        coordinator.join(TICKET_ONE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);
        coordinator.join(TICKET_TWO, PLAYER_TWO, RankedMatchmaker.Language.JAVA);
        UUID otherPlayer = new UUID(1, 3);

        RankedMatchmakingCoordinator.JoinResult result =
                coordinator.join(TICKET_ONE, otherPlayer, RankedMatchmaker.Language.JAVA);

        assertEquals(RankedMatchmakingCoordinator.Status.QUEUED, result.status());
    }

    @Test
    void d3Btl001KeepsQueueTicketsWhenTheAuthoritativeCommitFails() {
        FakeQueue queue = new FakeQueue();
        FakeMatches matches = new FakeMatches();
        matches.failCreate = true;
        RankedMatchmakingCoordinator coordinator = coordinator(queue, matches);
        coordinator.join(TICKET_ONE, PLAYER_ONE, RankedMatchmaker.Language.JAVA);

        assertThrows(
                IllegalStateException.class,
                () -> coordinator.join(TICKET_TWO, PLAYER_TWO, RankedMatchmaker.Language.JAVA));

        assertEquals(2, queue.entries.size());
    }

    private static RankedMatchmakingCoordinator coordinator(FakeQueue queue, FakeMatches matches) {
        return new RankedMatchmakingCoordinator(
                new RankedMatchmaker(new RankedMatchmaker.Policy(100, 50, Duration.ofSeconds(10), 300)),
                queue,
                matches,
                playerId -> 1_000,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(2),
                Duration.ofSeconds(5));
    }

    private static final class FakeQueue implements RankedQueueStore, RankedQueueStore.Lease {
        private final AtomicLong sequence = new AtomicLong();
        private final List<RankedMatchmaker.Entry> entries = new ArrayList<>();
        private boolean available = true;

        @Override
        public Optional<Lease> tryAcquire(RankedMatchmaker.Language language, Duration leaseTtl) {
            return available ? Optional.of(this) : Optional.empty();
        }

        @Override
        public RankedMatchmaker.Entry enqueue(Ticket ticket, Duration entryTtl) {
            return entries.stream()
                    .filter(entry -> entry.playerId().equals(ticket.playerId()))
                    .findFirst()
                    .orElseGet(() -> {
                        RankedMatchmaker.Entry entry = new RankedMatchmaker.Entry(
                                ticket.ticketId(),
                                ticket.playerId(),
                                ticket.language(),
                                ticket.publicRating(),
                                ticket.enqueuedAt(),
                                sequence.incrementAndGet());
                        entries.add(entry);
                        return entry;
                    });
        }

        @Override
        public List<RankedMatchmaker.Entry> activeEntries() {
            return List.copyOf(entries);
        }

        @Override
        public void remove(Collection<RankedMatchmaker.Entry> removed) {
            entries.removeAll(removed);
        }

        @Override
        public void close() {}
    }

    private static final class FakeMatches implements RankedMatchStore {
        private final Map<TicketOwner, UUID> tickets = new HashMap<>();
        private final Map<UUID, UUID> activeMatches = new HashMap<>();
        private int created;
        private boolean failCreate;
        private UUID conflictPlayerId;
        private UUID conflictMatchId;

        @Override
        public RankedMatch create(RankedMatchmaker.Pair pair, Instant createdAt) {
            if (failCreate) {
                throw new IllegalStateException("database unavailable");
            }
            if (conflictPlayerId != null
                    && (pair.playerOne().playerId().equals(conflictPlayerId)
                            || pair.playerTwo().playerId().equals(conflictPlayerId))) {
                activeMatches.put(conflictPlayerId, conflictMatchId);
                throw new ActiveRankedMatchConflictException(conflictPlayerId, conflictMatchId);
            }
            created++;
            UUID matchId = new UUID(3, created);
            tickets.put(new TicketOwner(pair.playerOne().ticketId(), pair.playerOne().playerId()), matchId);
            tickets.put(new TicketOwner(pair.playerTwo().ticketId(), pair.playerTwo().playerId()), matchId);
            activeMatches.put(pair.playerOne().playerId(), matchId);
            activeMatches.put(pair.playerTwo().playerId(), matchId);
            return new RankedMatch(matchId, new UUID(4, 1), pair.playerOne(), pair.playerTwo(), createdAt);
        }

        @Override
        public Optional<UUID> findMatchIdByTicket(UUID ticketId, UUID playerId) {
            return Optional.ofNullable(tickets.get(new TicketOwner(ticketId, playerId)));
        }

        @Override
        public Optional<UUID> findActiveMatchIdByPlayer(UUID playerId) {
            return Optional.ofNullable(activeMatches.get(playerId));
        }

        private record TicketOwner(UUID ticketId, UUID playerId) {}
    }
}
