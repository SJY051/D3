-- Identity owns handle uniqueness. Community is a projection and may receive cross-user rename events
-- out of order, so it must not reject a valid producer event because another projected row still has
-- the same handle.
alter table profile_projection
    drop constraint if exists profile_projection_handle_key;

create index profile_projection_handle_user_idx on profile_projection (handle, user_id);
