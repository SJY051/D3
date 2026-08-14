update match
set server_started_at = null,
    deadline_at = null,
    finished_at = null,
    void_reason = null
where status in ('LOBBY', 'READY')
  and (
      server_started_at is not null
      or deadline_at is not null
      or finished_at is not null
      or void_reason is not null
  );

with normalized_clock as (
    select id,
           greatest(coalesce(server_started_at, created_at), created_at) as started_at
    from match
    where status in ('RUNNING', 'JUDGING', 'FINISHED')
)
update match battle_match
set server_started_at = normalized_clock.started_at,
    deadline_at = case
        when battle_match.deadline_at > normalized_clock.started_at then battle_match.deadline_at
        else normalized_clock.started_at + interval '10 minutes'
    end,
    finished_at = case
        when battle_match.status = 'FINISHED' then
            greatest(coalesce(battle_match.finished_at, normalized_clock.started_at), normalized_clock.started_at)
        else null
    end,
    void_reason = case
        when battle_match.result = 'VOIDED' then battle_match.void_reason
        else null
    end
from normalized_clock
where battle_match.id = normalized_clock.id
  and (
      battle_match.server_started_at is distinct from normalized_clock.started_at
      or battle_match.deadline_at is null
      or battle_match.deadline_at <= normalized_clock.started_at
      or (battle_match.status = 'FINISHED' and (
          battle_match.finished_at is null
          or battle_match.finished_at < normalized_clock.started_at
      ))
      or (battle_match.status <> 'FINISHED' and battle_match.finished_at is not null)
      or (battle_match.result is distinct from 'VOIDED' and battle_match.void_reason is not null)
  );

update match_player player
set reconnect_deadline_at = case
        when player.connection_state = 'DISCONNECTED' then
            coalesce(player.reconnect_deadline_at, battle_match.created_at)
        else null
    end
from match battle_match
where battle_match.id = player.match_id
  and (
      (player.connection_state = 'DISCONNECTED' and player.reconnect_deadline_at is null)
      or (player.connection_state <> 'DISCONNECTED' and player.reconnect_deadline_at is not null)
  );

alter table match
    validate constraint match_terminal_finish_time_consistent,
    validate constraint match_clock_state_consistent,
    validate constraint match_start_after_creation,
    validate constraint match_finish_after_creation,
    validate constraint match_void_reason_consistent;

alter table match_player
    validate constraint match_player_reconnect_deadline_consistent;
