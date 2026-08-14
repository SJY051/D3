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
