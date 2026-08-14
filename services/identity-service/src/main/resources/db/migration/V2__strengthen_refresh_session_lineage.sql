alter table refresh_session
    drop constraint refresh_session_rotated_from_id_fkey,
    add constraint refresh_session_identity_unique unique (id, user_id),
    add constraint refresh_session_rotated_parent_unique unique (rotated_from_id),
    add constraint refresh_session_not_self_rotated
        check (rotated_from_id is null or rotated_from_id <> id),
    add constraint refresh_session_revocation_after_creation
        check (revoked_at is null or revoked_at >= created_at),
    add constraint refresh_session_parent_same_user_fk
        foreign key (rotated_from_id, user_id) references refresh_session(id, user_id);
