alter table match_projection
    add column player_one_user_id uuid,
    add column player_two_user_id uuid,
    add column projection_status text not null default 'ACTIVE';

create table match_projection_rebuild_queue (
    match_id uuid primary key,
    legacy_player_ids jsonb not null,
    result text not null,
    ranked boolean not null,
    score_summary jsonb,
    attack_summary jsonb,
    execution_summary jsonb,
    source_version bigint not null,
    projected_at timestamptz not null,
    quarantine_reason text not null,
    quarantined_at timestamptz not null default now(),
    constraint match_projection_rebuild_reason_supported
        check (quarantine_reason = 'INVALID_LEGACY_PLAYER_IDS')
);

insert into match_projection_rebuild_queue (
    match_id, legacy_player_ids, result, ranked, score_summary,
    attack_summary, execution_summary, source_version, projected_at,
    quarantine_reason
)
select match_id, player_ids, result, ranked, score_summary,
       attack_summary, execution_summary, source_version, projected_at,
       'INVALID_LEGACY_PLAYER_IDS'
from match_projection
where not (
    jsonb_typeof(player_ids) = 'array'
    and jsonb_array_length(player_ids) = 2
    and jsonb_typeof(player_ids -> 0) = 'string'
    and jsonb_typeof(player_ids -> 1) = 'string'
    and (player_ids ->> 0) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    and (player_ids ->> 1) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    and lower(player_ids ->> 0) <> lower(player_ids ->> 1)
);

update match_projection projection
set projection_status = 'REBUILD_REQUIRED'
from match_projection_rebuild_queue rebuild
where projection.match_id = rebuild.match_id;

update match_projection
set player_one_user_id = (player_ids ->> 0)::uuid,
    player_two_user_id = (player_ids ->> 1)::uuid
where projection_status = 'ACTIVE';

alter table match_projection
    add constraint match_projection_status_supported
        check (projection_status in ('ACTIVE', 'REBUILD_REQUIRED')),
    add constraint match_projection_player_state_consistent check (
        (
            projection_status = 'ACTIVE'
            and player_one_user_id is not null
            and player_two_user_id is not null
            and player_one_user_id <> player_two_user_id
        )
        or (
            projection_status = 'REBUILD_REQUIRED'
            and player_one_user_id is null
            and player_two_user_id is null
        )
    ),
    drop constraint match_projection_players_are_two_seats,
    drop column player_ids;
