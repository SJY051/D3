create type community_post_visibility as enum ('PUBLIC', 'FOLLOWERS', 'CIRCLE', 'PRIVATE');

create table post (
    id uuid primary key,
    author_user_id uuid not null,
    visibility community_post_visibility not null,
    prose_markdown text not null,
    rendered_html text not null,
    prose_character_count int not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create index post_author_created_idx on post (author_user_id, created_at desc, id desc);
create index post_public_feed_idx on post (created_at desc, id desc) where visibility = 'PUBLIC';
