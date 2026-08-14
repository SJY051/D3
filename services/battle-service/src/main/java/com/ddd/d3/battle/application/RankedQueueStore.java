package com.ddd.d3.battle.application;

import com.ddd.d3.battle.domain.RankedMatchmaker;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface RankedQueueStore {

    record Ticket(
            UUID ticketId,
            UUID playerId,
            RankedMatchmaker.Language language,
            int publicRating,
            Instant enqueuedAt) {
        public Ticket {
            Objects.requireNonNull(ticketId, "ticketId must not be null");
            Objects.requireNonNull(playerId, "playerId must not be null");
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(enqueuedAt, "enqueuedAt must not be null");
        }
    }

    Optional<Lease> tryAcquire(RankedMatchmaker.Language language, Duration leaseTtl);

    interface Lease extends AutoCloseable {

        RankedMatchmaker.Entry enqueue(Ticket ticket, Duration entryTtl);

        List<RankedMatchmaker.Entry> activeEntries();

        void remove(Collection<RankedMatchmaker.Entry> entries);

        @Override
        void close();
    }
}
