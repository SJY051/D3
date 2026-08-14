package com.ddd.d3.battle.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RankedMatchmakerTest {

    private static final Instant START = Instant.parse("2026-08-14T00:00:00Z");
    private static final RankedMatchmaker.Policy POLICY =
            new RankedMatchmaker.Policy(100, 50, Duration.ofSeconds(10), 300);

    @Test
    void d3Btl001PairsTheOldestClosestCompatiblePlayersOnce() {
        RankedMatchmaker.Entry oldest = entry(1, RankedMatchmaker.Language.PYTHON3, 1_000, 0, 1);
        RankedMatchmaker.Entry closest = entry(2, RankedMatchmaker.Language.PYTHON3, 1_060, 1, 2);
        RankedMatchmaker.Entry remaining = entry(3, RankedMatchmaker.Language.PYTHON3, 1_080, 2, 3);
        RankedMatchmaker.Entry otherLanguage = entry(4, RankedMatchmaker.Language.JAVA, 1_010, 0, 4);
        RankedMatchmaker matchmaker = new RankedMatchmaker(POLICY);

        List<RankedMatchmaker.Pair> pairs = matchmaker.pair(
                List.of(remaining, otherLanguage, closest, oldest), START.plusSeconds(5));

        assertEquals(List.of(new RankedMatchmaker.Pair(oldest, closest)), pairs);
    }

    @Test
    void d3Btl001WidensTheRatingWindowOnlyAtCompletedIntervals() {
        RankedMatchmaker.Entry first = entry(1, RankedMatchmaker.Language.CPP, 1_000, 0, 1);
        RankedMatchmaker.Entry second = entry(2, RankedMatchmaker.Language.CPP, 1_180, 0, 2);
        RankedMatchmaker matchmaker = new RankedMatchmaker(POLICY);

        assertEquals(List.of(), matchmaker.pair(List.of(first, second), START.plusSeconds(19)));
        assertEquals(
                List.of(new RankedMatchmaker.Pair(first, second)),
                matchmaker.pair(List.of(first, second), START.plusSeconds(20)));
    }

    @Test
    void d3Btl001RequiresBothPlayersRatingWindowsToAcceptThePair() {
        RankedMatchmaker.Entry waiting = entry(1, RankedMatchmaker.Language.JAVA, 1_000, 0, 1);
        RankedMatchmaker.Entry newcomer = entry(2, RankedMatchmaker.Language.JAVA, 1_250, 40, 2);
        RankedMatchmaker matchmaker = new RankedMatchmaker(POLICY);

        assertEquals(List.of(), matchmaker.pair(List.of(waiting, newcomer), START.plusSeconds(40)));
        assertEquals(
                List.of(new RankedMatchmaker.Pair(waiting, newcomer)),
                matchmaker.pair(List.of(waiting, newcomer), START.plusSeconds(70)));
    }

    @Test
    void d3Btl001RejectsDuplicateQueueEntriesForOnePlayer() {
        RankedMatchmaker.Entry first = entry(1, RankedMatchmaker.Language.C, 1_000, 0, 1);
        RankedMatchmaker.Entry duplicate = entry(1, RankedMatchmaker.Language.C, 1_010, 1, 2);
        RankedMatchmaker matchmaker = new RankedMatchmaker(POLICY);

        assertThrows(
                IllegalArgumentException.class,
                () -> matchmaker.pair(List.of(first, duplicate), START.plusSeconds(2)));
    }

    @Test
    void d3Btl001ScopesIdempotencyTicketsToEachPlayer() {
        RankedMatchmaker.Entry first = entry(1, RankedMatchmaker.Language.C, 1_000, 0, 1);
        RankedMatchmaker.Entry second = new RankedMatchmaker.Entry(
                first.ticketId(),
                new UUID(0, 2),
                RankedMatchmaker.Language.C,
                1_010,
                START.plusSeconds(1),
                2);
        RankedMatchmaker matchmaker = new RankedMatchmaker(POLICY);

        assertEquals(
                List.of(new RankedMatchmaker.Pair(first, second)),
                matchmaker.pair(List.of(first, second), START.plusSeconds(2)));
    }

    @Test
    void d3Btl001CapsExtremeWideningWithoutArithmeticOverflow() {
        RankedMatchmaker.Entry first = entry(1, RankedMatchmaker.Language.CPP, 0, 0, 1);
        RankedMatchmaker.Entry second = entry(2, RankedMatchmaker.Language.CPP, Integer.MAX_VALUE, 0, 2);
        RankedMatchmaker matchmaker = new RankedMatchmaker(new RankedMatchmaker.Policy(
                0, Integer.MAX_VALUE, Duration.ofNanos(1), Integer.MAX_VALUE));

        assertEquals(
                List.of(new RankedMatchmaker.Pair(first, second)),
                matchmaker.pair(List.of(first, second), START.plusSeconds(10)));
    }

    private static RankedMatchmaker.Entry entry(
            long playerId,
            RankedMatchmaker.Language language,
            int rating,
            long queuedAfterSeconds,
            long sequence) {
        return new RankedMatchmaker.Entry(
                new UUID(1, sequence),
                new UUID(0, playerId),
                language,
                rating,
                START.plusSeconds(queuedAfterSeconds),
                sequence);
    }
}
