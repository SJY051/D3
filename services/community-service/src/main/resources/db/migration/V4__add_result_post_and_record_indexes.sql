alter table post
    add column post_kind text not null default 'USER',
    add column match_source_version bigint,
    add constraint post_kind_supported
        check (post_kind in ('USER', 'MATCH_RESULT')),
    add constraint post_match_source_version_non_negative
        check (match_source_version is null or match_source_version >= 0),
    add constraint post_result_metadata_consistent check (
        (post_kind = 'USER' and match_source_version is null)
        or (
            post_kind = 'MATCH_RESULT'
            and match_projection_id is not null
            and match_source_version is not null
        )
    );

create unique index post_result_match_unique_idx
    on post (match_projection_id)
    where post_kind = 'MATCH_RESULT';

create index match_projection_player_one_projected_idx
    on match_projection (player_one_user_id, projected_at desc, match_id desc)
    where projection_status = 'ACTIVE';

create index match_projection_player_two_projected_idx
    on match_projection (player_two_user_id, projected_at desc, match_id desc)
    where projection_status = 'ACTIVE';
