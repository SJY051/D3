alter table match_player
    drop constraint match_player_connection_state_supported,
    add constraint match_player_connection_state_supported
        check (connection_state in ('CONNECTING', 'CONNECTED', 'DISCONNECTED'));

alter table match_player
    add column queue_ticket_id uuid;

alter table match_player
    add column ready boolean not null default false,
    add column connection_generation bigint not null default 0;

update match_player player
set ready = true
from match battle_match
where battle_match.id = player.match_id
  and battle_match.status in ('READY', 'RUNNING', 'JUDGING', 'FINISHED');

update match_player
set connection_generation = 1
where connection_state = 'DISCONNECTED';

alter table match_player
    add constraint match_player_connection_generation_non_negative
        check (connection_generation >= 0),
    add constraint match_player_disconnect_generation_consistent check (
        connection_state <> 'DISCONNECTED' or connection_generation > 0
    ),
    add constraint match_player_ready_connection_consistent check (
        ready = false or connection_state <> 'CONNECTING'
    );

alter table match
    add column resolution_reason text,
    add constraint match_resolution_reason_supported check (
        resolution_reason is null
        or resolution_reason in ('SURRENDER', 'DISCONNECT_TIMEOUT', 'PLATFORM_INCIDENT')
    ),
    add constraint match_resolution_reason_state_consistent check (
        resolution_reason is null or status = 'FINISHED'
    );

create unique index match_player_user_queue_ticket_unique_idx
    on match_player (user_id, queue_ticket_id)
    where queue_ticket_id is not null;
