alter table match_projection
    add column player_one_user_id uuid,
    add column player_two_user_id uuid;

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
