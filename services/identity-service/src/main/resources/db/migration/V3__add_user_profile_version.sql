-- user-profile.changed.v1 carries a monotonic profileVersion so Community can ignore reordered
-- deliveries. Identity owns it: 0 at registration, +1 on each profile change, published in the same
-- transaction as the write via outbox_event (aggregate_version = profile_version).
alter table user_account
    add column profile_version bigint not null default 0;

alter table user_account
    add constraint user_account_profile_version_non_negative check (profile_version >= 0);
