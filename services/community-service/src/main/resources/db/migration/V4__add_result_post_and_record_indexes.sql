with ranked_result_posts as (
    select id,
           row_number() over (
               partition by match_projection_id
               order by created_at, id
           ) as reference_position
    from post
    where match_projection_id is not null
)
update post
set match_projection_id = null
from ranked_result_posts
where post.id = ranked_result_posts.id
  and ranked_result_posts.reference_position > 1;

create unique index post_match_projection_unique_idx
    on post (match_projection_id)
    where match_projection_id is not null;

create index match_projection_player_one_projected_idx
    on match_projection (player_one_user_id, projected_at desc, match_id desc)
    where projection_status = 'ACTIVE';

create index match_projection_player_two_projected_idx
    on match_projection (player_two_user_id, projected_at desc, match_id desc)
    where projection_status = 'ACTIVE';
