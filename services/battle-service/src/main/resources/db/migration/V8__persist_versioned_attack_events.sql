create table attack_event_legacy (like attack_event including all);

alter table attack_event_legacy
    add column archived_at timestamptz not null default now();

insert into attack_event_legacy (
    id, match_id, sequence, actor_user_id, target_user_id,
    attack_type, resolution, energy_cost, occurred_at
)
select
    id, match_id, sequence, actor_user_id, target_user_id,
    attack_type, resolution, energy_cost, occurred_at
from attack_event;

delete from attack_event;

alter table attack_event
    add column payload_version smallint not null,
    add column event_payload jsonb not null,
    add constraint attack_event_payload_version_supported
        check (payload_version = 1),
    add constraint attack_event_v1_payload_consistent check (
        jsonb_typeof(event_payload) = 'object'
        and event_payload ?& array[
            'sequence', 'type', 'playerId', 'key',
            'energyDelta', 'energyAfter', 'occurredAt'
        ]
        and (event_payload ->> 'sequence')::bigint = sequence
        and event_payload ->> 'type' = attack_type
        and event_payload ->> 'playerId' = actor_user_id::text
    );

create function enforce_attack_event_sequence_continuity()
returns trigger
language plpgsql
as $$
declare
    affected_match_id uuid := coalesce(new.match_id, old.match_id);
    first_sequence bigint;
    last_sequence bigint;
    event_count bigint;
begin
    select min(sequence), max(sequence), count(*)
    into first_sequence, last_sequence, event_count
    from attack_event
    where match_id = affected_match_id;

    if event_count > 0 and (first_sequence <> 1 or last_sequence <> event_count) then
        raise exception 'attack event sequence for match % must be contiguous from 1', affected_match_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger attack_event_sequence_contiguous
    after insert or update or delete on attack_event
    deferrable initially deferred
    for each row execute function enforce_attack_event_sequence_continuity();

alter table match_command_receipt
    drop constraint match_command_receipt_type_supported,
    add constraint match_command_receipt_type_supported check (
        command_type in (
            'READY', 'DISCONNECT', 'RECONNECT', 'SURRENDER',
            'ATTACK_LAUNCH', 'ATTACK_BLOCK', 'ATTACK_REFLECT'
        )
    );
