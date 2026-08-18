-- user-profile.changed.v1 carries a monotonic profileVersion so Community can ignore reordered
-- deliveries. Identity owns it: 0 at registration, +1 on each profile change, published in the same
-- transaction as the write via outbox_event (aggregate_version = profile_version).
alter table user_account
    add column profile_version bigint not null default 0;

alter table user_account
    add constraint user_account_profile_version_non_negative check (profile_version >= 0);

-- Accounts that predate the producer never emitted a projection event, so backfill a v0
-- user-profile.changed outbox row for each. Without this, an existing account never reaches Community's
-- profile_projection and is invisible to handle search. The envelope matches the application producer;
-- the scheduled publisher relays these like any other row, and the consumer's inbox makes it idempotent.
-- A fresh install has no accounts and backfills nothing.
-- One gen_random_uuid() per account (evaluated per row in the CTE), reused for both the row id and the
-- envelope eventId so they match the application producer.
with seeded as materialized (
    select ua.id as user_id, ua.handle, ua.updated_at, gen_random_uuid() as event_id
    from user_account ua
)
insert into outbox_event (id, aggregate_id, aggregate_version, event_type, payload, occurred_at, published_at)
select
    seeded.event_id,
    seeded.user_id,
    0,
    'user-profile.changed',
    jsonb_build_object(
        'eventId', seeded.event_id::text,
        'eventType', 'user-profile.changed',
        'version', 1,
        'occurredAt', to_char(seeded.updated_at at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'correlationId', seeded.user_id::text,
        'aggregateId', seeded.user_id::text,
        'aggregateVersion', 0,
        'data', jsonb_build_object('userId', seeded.user_id::text, 'handle', seeded.handle, 'profileVersion', 0)
    ),
    seeded.updated_at,
    null
from seeded
on conflict (aggregate_id, aggregate_version, event_type) do nothing;
