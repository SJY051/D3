create table match_command_receipt (
    command_id uuid primary key,
    match_id uuid not null,
    player_user_id uuid not null,
    command_type text not null,
    payload_fingerprint text not null,
    aggregate_version bigint not null,
    accepted_at timestamptz not null,
    constraint match_command_receipt_player_fk
        foreign key (match_id, player_user_id) references match_player(match_id, user_id),
    constraint match_command_receipt_type_supported check (
        command_type in ('READY', 'DISCONNECT', 'RECONNECT', 'SURRENDER')
    ),
    constraint match_command_receipt_payload_not_blank
        check (btrim(payload_fingerprint) <> ''),
    constraint match_command_receipt_version_non_negative
        check (aggregate_version >= 0)
);

create index match_command_receipt_match_version_idx
    on match_command_receipt (match_id, aggregate_version, command_id);
