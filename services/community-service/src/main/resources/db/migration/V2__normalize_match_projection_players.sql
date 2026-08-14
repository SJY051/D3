alter table match_projection
    add column player_one_user_id uuid,
    add column player_two_user_id uuid;

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

delete from match_projection projection
using match_projection_rebuild_queue rebuild
where projection.match_id = rebuild.match_id;

update match_projection
set player_one_user_id = (player_ids ->> 0)::uuid,
    player_two_user_id = (player_ids ->> 1)::uuid;

alter table match_projection
    alter column player_one_user_id set not null,
    alter column player_two_user_id set not null,
    add constraint match_projection_players_distinct
        check (player_one_user_id <> player_two_user_id),
    drop constraint match_projection_players_are_two_seats,
    drop column player_ids;
