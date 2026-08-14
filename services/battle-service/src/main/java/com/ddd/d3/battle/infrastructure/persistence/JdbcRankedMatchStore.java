package com.ddd.d3.battle.infrastructure.persistence;

import com.ddd.d3.battle.application.RankedMatchStore;
import com.ddd.d3.battle.application.NoActiveRankedProblemException;
import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public final class JdbcRankedMatchStore implements RankedMatchStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcRankedMatchStore(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public RankedMatch create(RankedMatchmaker.Pair requestedPair, Instant createdAt) {
        Objects.requireNonNull(requestedPair, "pair must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        RankedMatchmaker.Pair pair = canonicalPair(requestedPair);
        UUID matchId = deterministicMatchId(pair);
        return Objects.requireNonNull(transactions.execute(status -> createInsideTransaction(matchId, pair, createdAt)));
    }

    @Override
    public Optional<UUID> findMatchIdByTicket(UUID ticketId, UUID playerId) {
        Objects.requireNonNull(ticketId, "ticketId must not be null");
        Objects.requireNonNull(playerId, "playerId must not be null");
        return jdbc.sql("""
                        select match_id
                        from match_player
                        where queue_ticket_id = :ticketId and user_id = :playerId
                        """)
                .param("ticketId", ticketId)
                .param("playerId", playerId)
                .query(UUID.class)
                .optional();
    }

    private RankedMatch createInsideTransaction(UUID matchId, RankedMatchmaker.Pair pair, Instant createdAt) {
        Optional<RankedMatch> existing = findExisting(matchId, pair);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        UUID problemId = jdbc.sql("""
                        select id
                        from problem
                        where active = true
                        order by created_at, id
                        limit 1
                        """)
                .query(UUID.class)
                .optional()
                .orElseThrow(NoActiveRankedProblemException::new);

        int inserted = jdbc.sql("""
                        insert into match (id, problem_id, ranked, status, created_at)
                        values (:matchId, :problemId, true, 'LOBBY', :createdAt)
                        on conflict (id) do nothing
                        """)
                .param("matchId", matchId)
                .param("problemId", problemId)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
        if (inserted == 0) {
            return findExisting(matchId, pair)
                    .orElseThrow(() -> new IllegalStateException("Conflicting ranked match identity"));
        }

        insertPlayer(matchId, pair.playerOne(), 1);
        insertPlayer(matchId, pair.playerTwo(), 2);
        return new RankedMatch(matchId, problemId, pair.playerOne(), pair.playerTwo(), createdAt);
    }

    private Optional<RankedMatch> findExisting(UUID matchId, RankedMatchmaker.Pair pair) {
        Optional<ExistingMatch> match = jdbc.sql("""
                        select problem_id, created_at
                        from match
                        where id = :matchId and ranked = true and status = 'LOBBY'
                """)
                .param("matchId", matchId)
                .query((resultSet, rowNumber) -> new ExistingMatch(
                        resultSet.getObject("problem_id", UUID.class),
                        resultSet.getTimestamp("created_at").toInstant()))
                .optional();
        if (match.isEmpty()) {
            return Optional.empty();
        }

        List<ExistingPlayer> players = jdbc.sql("""
                        select user_id, seat, language_key, queue_ticket_id
                        from match_player
                        where match_id = :matchId
                        order by seat
                        """)
                .param("matchId", matchId)
                .query(ExistingPlayer.class)
                .list();
        if (players.size() != 2
                || !matches(players.get(0), pair.playerOne(), 1)
                || !matches(players.get(1), pair.playerTwo(), 2)) {
            throw new IllegalStateException("Conflicting ranked match participants");
        }
        ExistingMatch existing = match.orElseThrow();
        return Optional.of(new RankedMatch(
                matchId,
                existing.problemId(),
                pair.playerOne(),
                pair.playerTwo(),
                existing.createdAt()));
    }

    private void insertPlayer(UUID matchId, RankedMatchmaker.Entry player, int seat) {
        jdbc.sql("""
                        insert into match_player (
                            match_id, user_id, seat, language_key, connection_state, queue_ticket_id
                        ) values (
                            :matchId, :userId, :seat, :language, 'CONNECTING', :queueTicketId
                        )
                        """)
                .param("matchId", matchId)
                .param("userId", player.playerId())
                .param("seat", seat)
                .param("language", player.language().name())
                .param("queueTicketId", player.ticketId())
                .update();
    }

    private static boolean matches(ExistingPlayer existing, RankedMatchmaker.Entry expected, int seat) {
        return existing.userId().equals(expected.playerId())
                && existing.seat() == seat
                && existing.languageKey().equals(expected.language().name())
                && existing.queueTicketId().equals(expected.ticketId());
    }

    private static RankedMatchmaker.Pair canonicalPair(RankedMatchmaker.Pair pair) {
        RankedMatchmaker.Entry first = Objects.requireNonNull(pair.playerOne(), "playerOne must not be null");
        RankedMatchmaker.Entry second = Objects.requireNonNull(pair.playerTwo(), "playerTwo must not be null");
        if (first.playerId().equals(second.playerId())
                || first.ticketId().equals(second.ticketId())
                || first.language() != second.language()) {
            throw new IllegalArgumentException("ranked pair must contain distinct same-language players");
        }
        int ticketOrder = first.ticketId().compareTo(second.ticketId());
        return ticketOrder < 0 || (ticketOrder == 0 && first.playerId().compareTo(second.playerId()) <= 0)
                ? new RankedMatchmaker.Pair(first, second)
                : new RankedMatchmaker.Pair(second, first);
    }

    private static UUID deterministicMatchId(RankedMatchmaker.Pair pair) {
        String key = "d3:ranked:v1:"
                + pair.playerOne().playerId() + ':' + pair.playerOne().ticketId()
                + ':' + pair.playerTwo().playerId() + ':' + pair.playerTwo().ticketId();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private record ExistingMatch(UUID problemId, Instant createdAt) {}

    private record ExistingPlayer(UUID userId, int seat, String languageKey, UUID queueTicketId) {}
}
