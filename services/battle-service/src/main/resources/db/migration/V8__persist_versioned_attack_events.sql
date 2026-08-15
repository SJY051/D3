alter table attack_event
    add column payload_version smallint,
    add column event_payload jsonb;

update attack_event
set payload_version = 0,
    event_payload = jsonb_build_object(
        'id', id,
        'matchId', match_id,
        'sequence', sequence,
        'actorUserId', actor_user_id,
        'targetUserId', target_user_id,
        'attackType', attack_type,
        'resolution', resolution,
        'energyCost', energy_cost,
        'occurredAt', occurred_at
    );

alter table attack_event
    alter column payload_version set not null,
    alter column event_payload set not null,
    add constraint attack_event_payload_version_supported
        check (payload_version in (0, 1)),
    add constraint attack_event_v1_payload_consistent check (
        payload_version = 0
        or (
            jsonb_typeof(event_payload) = 'object'
            and event_payload ?& array[
                'sequence', 'type', 'playerId', 'key',
                'energyDelta', 'energyAfter', 'occurredAt'
            ]
            and (event_payload ->> 'sequence')::bigint = sequence
            and event_payload ->> 'type' = attack_type
            and event_payload ->> 'playerId' = actor_user_id::text
        )
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
