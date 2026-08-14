create sequence battle_connection_generation_seq
    as bigint
    minvalue 1;

select setval(
    'battle_connection_generation_seq',
    greatest(
        (select coalesce(max(connection_generation), 0) + 1 from match_player),
        1
    ),
    false
);
