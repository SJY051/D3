alter table match_projection
    add column player_records jsonb;

alter table match_projection
    add constraint match_projection_player_records_shape
        check (
            player_records is null
            or (jsonb_typeof(player_records) = 'array' and jsonb_array_length(player_records) = 2)
        );
