package com.ddd.d3.community.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projects {@code rating.changed.v1} onto the rating columns of {@code profile_projection}.
 *
 * <p>Rating and identity are two independent producers of the same row, each owning its own columns
 * and source version. A rating may arrive before the (gated) identity projection creates the row, so
 * this upserts a rating-first row with null identity columns (see V5); the later
 * {@code user-profile.changed.v1} projection fills {@code handle}/{@code identity_source_version}
 * without touching rating. No rating is dropped.
 */
public final class JdbcProfileRatingStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcProfileRatingStore(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    /**
     * Claims the event in the inbox and upserts the rating projection in one transaction. Exactly-once
     * is enforced by the inbox uniqueness. An absent profile row is inserted as a rating-first row; a
     * stale (lower rating_source_version) row is left unchanged. Either way the event is consumed once.
     *
     * @return true when this delivery was applied, false when it was already claimed.
     */
    public boolean apply(RatingChangedEvent event) {
        Objects.requireNonNull(event, "event");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!claim(event)) {
                return false;
            }
            jdbc.sql("""
                            insert into profile_projection (
                                user_id, public_rating, rp, tier, rating_source_version, projected_at
                            ) values (
                                :userId, :ratingAfter, :seasonRpAfter, :tierAfter,
                                :aggregateVersion, :receivedAt
                            )
                            on conflict (user_id) do update
                            set public_rating = excluded.public_rating,
                                rp = excluded.rp,
                                tier = excluded.tier,
                                rating_source_version = excluded.rating_source_version,
                                projected_at = excluded.projected_at
                            where profile_projection.rating_source_version is null
                               or profile_projection.rating_source_version < excluded.rating_source_version
                            """)
                    .param("userId", event.userId())
                    .param("ratingAfter", event.ratingAfter())
                    .param("seasonRpAfter", event.seasonRpAfter())
                    .param("tierAfter", event.tierAfter())
                    .param("aggregateVersion", event.aggregateVersion())
                    .param("receivedAt", Timestamp.from(event.receivedAt()))
                    .update();
            int applied = jdbc.sql("""
                            update inbox_event set applied_at = :appliedAt
                            where event_id = :eventId and applied_at is null
                            """)
                    .param("appliedAt", Timestamp.from(event.receivedAt()))
                    .param("eventId", event.eventId())
                    .update();
            if (applied != 1) {
                throw new IllegalStateException("Claimed rating.changed event was not pending");
            }
            return true;
        }));
    }

    private boolean claim(RatingChangedEvent event) {
        return jdbc.sql("""
                        insert into inbox_event (
                            event_id, event_type, aggregate_id, aggregate_version, received_at, applied_at
                        ) values (
                            :eventId, 'rating.changed', :aggregateId, :aggregateVersion, :receivedAt, null
                        )
                        on conflict do nothing
                        """)
                .param("eventId", event.eventId())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .update() == 1;
    }

    /** A rating.changed.v1 event flattened to the profile-rating columns it projects. */
    public record RatingChangedEvent(
            UUID eventId,
            UUID aggregateId,
            long aggregateVersion,
            UUID userId,
            int ratingAfter,
            int seasonRpAfter,
            String tierAfter,
            Instant receivedAt) {}
}
