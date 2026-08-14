package com.ddd.d3.battle.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class RankedMatchmaker {

    public enum Language {
        C,
        CPP,
        JAVA,
        PYTHON3,
        JAVASCRIPT,
        TYPESCRIPT
    }

    public record Entry(
            UUID ticketId,
            UUID playerId,
            Language language,
            int publicRating,
            Instant enqueuedAt,
            long sequence) {
        public Entry {
            Objects.requireNonNull(ticketId, "ticketId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must not be negative");
            }
        }
    }

    public record Pair(Entry playerOne, Entry playerTwo) {}

    public record Policy(
            int initialRatingWindow,
            int wideningStep,
            Duration wideningInterval,
            int maximumRatingWindow) {}

    private static final Comparator<Entry> WAIT_ORDER = Comparator.comparing(Entry::enqueuedAt)
            .thenComparingLong(Entry::sequence)
            .thenComparing(Entry::playerId);

    private final Policy policy;

    public RankedMatchmaker(Policy policy) {
        this.policy = validatePolicy(policy);
    }

    public List<Pair> pair(List<Entry> entries, Instant now) {
        Objects.requireNonNull(entries, "entries must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<Entry> unmatched = new ArrayList<>(entries.size());
        Set<UUID> playerIds = new HashSet<>();
        for (Entry entry : entries) {
            validateEntry(entry, now);
            if (!playerIds.add(entry.playerId())) {
                throw new IllegalArgumentException("duplicate playerId: " + entry.playerId());
            }
            unmatched.add(entry);
        }
        unmatched.sort(WAIT_ORDER);

        List<Pair> pairs = new ArrayList<>();
        while (!unmatched.isEmpty()) {
            Entry anchor = unmatched.removeFirst();
            Entry candidate = unmatched.stream()
                    .filter(entry -> isCompatible(anchor, entry, now))
                    .min(Comparator.comparingLong((Entry entry) -> ratingGap(anchor, entry))
                            .thenComparing(WAIT_ORDER))
                    .orElse(null);
            if (candidate != null) {
                unmatched.remove(candidate);
                pairs.add(new Pair(anchor, candidate));
            }
        }
        return List.copyOf(pairs);
    }

    private boolean isCompatible(Entry first, Entry second, Instant now) {
        long gap = ratingGap(first, second);
        return first.language() == second.language()
                && gap <= ratingWindow(first, now)
                && gap <= ratingWindow(second, now);
    }

    private long ratingWindow(Entry entry, Instant now) {
        if (policy.wideningStep() == 0
                || policy.initialRatingWindow() == policy.maximumRatingWindow()) {
            return policy.initialRatingWindow();
        }
        long intervalsToMaximum = ((long) policy.maximumRatingWindow()
                        - policy.initialRatingWindow()
                        + policy.wideningStep()
                        - 1L)
                / policy.wideningStep();
        long elapsedIntervals;
        try {
            elapsedIntervals = Duration.between(entry.enqueuedAt(), now)
                    .dividedBy(policy.wideningInterval());
        } catch (ArithmeticException exception) {
            return policy.maximumRatingWindow();
        }
        if (elapsedIntervals >= intervalsToMaximum) {
            return policy.maximumRatingWindow();
        }
        return (long) policy.initialRatingWindow() + elapsedIntervals * policy.wideningStep();
    }

    private static long ratingGap(Entry first, Entry second) {
        return Math.abs((long) first.publicRating() - second.publicRating());
    }

    private static Policy validatePolicy(Policy policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(policy.wideningInterval(), "wideningInterval must not be null");
        if (policy.initialRatingWindow() < 0
                || policy.wideningStep() < 0
                || policy.wideningInterval().isZero()
                || policy.wideningInterval().isNegative()
                || policy.maximumRatingWindow() < policy.initialRatingWindow()) {
            throw new IllegalArgumentException("invalid ranked matchmaking policy");
        }
        return policy;
    }

    private static void validateEntry(Entry entry, Instant now) {
        Objects.requireNonNull(entry, "entry must not be null");
        if (entry.enqueuedAt().isAfter(now)) {
            throw new IllegalArgumentException("invalid ranked matchmaking entry");
        }
    }
}
