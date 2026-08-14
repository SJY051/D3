alter table match
    drop constraint match_resolution_reason_supported,
    add constraint match_resolution_reason_supported check (
        resolution_reason is null
        or resolution_reason in (
            'SURRENDER',
            'DISCONNECT_TIMEOUT',
            'PLATFORM_INCIDENT',
            'LEGACY_IMPORT'
        )
    );

with normalized_terminal_clock as (
    select id,
           greatest(coalesce(server_started_at, created_at), created_at) as started_at
    from match
    where status = 'FINISHED'
)
update match battle_match
set server_started_at = normalized_terminal_clock.started_at,
    deadline_at = case
        when battle_match.deadline_at > normalized_terminal_clock.started_at then battle_match.deadline_at
        else normalized_terminal_clock.started_at + interval '10 minutes'
    end,
    finished_at = greatest(
        coalesce(battle_match.finished_at, normalized_terminal_clock.started_at),
        normalized_terminal_clock.started_at
    )
from normalized_terminal_clock
where battle_match.id = normalized_terminal_clock.id
  and (
      battle_match.server_started_at is distinct from normalized_terminal_clock.started_at
      or battle_match.deadline_at is null
      or battle_match.deadline_at <= normalized_terminal_clock.started_at
      or battle_match.finished_at is null
      or battle_match.finished_at < normalized_terminal_clock.started_at
  );

update match
set void_reason = 'legacy-import'
where result = 'VOIDED'
  and nullif(btrim(void_reason), '') is null;

update match
set resolution_reason = case
        when result = 'VOIDED' then 'PLATFORM_INCIDENT'
        else 'LEGACY_IMPORT'
    end
where status = 'FINISHED'
  and result is not null
  and resolution_reason is null;

alter table match
    add constraint match_terminal_resolution_reason_consistent check (
        (status = 'FINISHED') = (resolution_reason is not null)
    ),
    add constraint match_void_reason_required check (
        (result = 'VOIDED') = (nullif(btrim(void_reason), '') is not null)
    );
