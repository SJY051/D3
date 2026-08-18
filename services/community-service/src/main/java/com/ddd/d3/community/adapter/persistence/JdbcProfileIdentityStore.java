package com.ddd.d3.community.adapter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Projects {@code user-profile.changed.v1} onto the identity-owned columns of {@code profile_projection}.
 *
 * <p>Identity and rating are two independent producers of the same row, each owning its own columns and
 * source version (see V5). This store owns {@code handle} and {@code identity_source_version}. A rating may
 * create the row first, so an upsert here fills the handle on a rating-first row; and a reordered delivery
 * carrying a lower {@code identity_source_version} is claimed but leaves the row unchanged. Rating columns
 * are never touched. Idempotency comes from the inbox claim, mirroring {@link JdbcProfileRatingStore}.
 */
public final class JdbcProfileIdentityStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public JdbcProfileIdentityStore(JdbcClient jdbc, TransactionTemplate transaction) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    /**
     * Claims the event in the inbox and upserts the identity projection in one transaction. Exactly-once is
     * enforced by the inbox uniqueness. An absent row is inserted identity-first (rating columns null); a
     * stale (lower or equal identity_source_version) row is left unchanged. Either way the event is consumed
     * once.
     *
     * @return true when this delivery was applied, false when it was already claimed.
     */
    public boolean apply(UserProfileChangedEvent event) {
        Objects.requireNonNull(event, "event");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (!claim(event)) {
                return false;
            }
            jdbc.sql("""
                            insert into profile_projection (
                                user_id, handle, identity_source_version, projected_at
                            ) values (
                                :userId, :handle, :identitySourceVersion, :receivedAt
                            )
                            on conflict (user_id) do update
                            set handle = excluded.handle,
                                identity_source_version = excluded.identity_source_version,
                                projected_at = excluded.projected_at
                            where profile_projection.identity_source_version is null
                               or profile_projection.identity_source_version < excluded.identity_source_version
                            """)
                    .param("userId", event.userId())
                    .param("handle", event.handle())
                    .param("identitySourceVersion", event.aggregateVersion())
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
                throw new IllegalStateException("Claimed user-profile.changed event was not pending");
            }
            return true;
        }));
    }

    private boolean claim(UserProfileChangedEvent event) {
        return jdbc.sql("""
                        insert into inbox_event (
                            event_id, event_type, aggregate_id, aggregate_version, received_at, applied_at
                        ) values (
                            :eventId, 'user-profile.changed', :aggregateId, :aggregateVersion, :receivedAt, null
                        )
                        on conflict do nothing
                        """)
                .param("eventId", event.eventId())
                .param("aggregateId", event.aggregateId())
                .param("aggregateVersion", event.aggregateVersion())
                .param("receivedAt", Timestamp.from(event.receivedAt()))
                .update() == 1;
    }

    /** A user-profile.changed.v1 event flattened to the identity-owned columns it projects. */
    public record UserProfileChangedEvent(
            UUID eventId,
            UUID aggregateId,
            long aggregateVersion,
            UUID userId,
            String handle,
            Instant receivedAt) {}
}
