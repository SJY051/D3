create table circle (
    id uuid primary key,
    owner_user_id uuid not null,
    name text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint circle_name_not_blank check (btrim(name) <> ''),
    constraint circle_owner_name_unique unique (owner_user_id, name)
);

create table circle_member (
    circle_id uuid not null references circle(id),
    member_user_id uuid not null,
    added_at timestamptz not null,
    primary key (circle_id, member_user_id)
);

create index circle_member_user_circle_idx on circle_member (member_user_id, circle_id);

create table follow (
    follower_user_id uuid not null,
    followed_user_id uuid not null,
    created_at timestamptz not null,
    primary key (follower_user_id, followed_user_id),
    constraint follow_distinct_users check (follower_user_id <> followed_user_id)
);

create index follow_followed_created_idx on follow (followed_user_id, created_at desc);

create table profile_projection (
    user_id uuid primary key,
    handle text not null unique,
    display_name text,
    public_rating integer,
    rp integer,
    tier text,
    division text,
    peak_tier text,
    identity_source_version bigint not null,
    rating_source_version bigint,
    projected_at timestamptz not null,
    constraint profile_projection_handle_not_blank check (btrim(handle) <> ''),
    constraint profile_projection_identity_version_non_negative check (identity_source_version >= 0),
    constraint profile_projection_rating_version_non_negative
        check (rating_source_version is null or rating_source_version >= 0)
);

create table match_projection (
    match_id uuid primary key,
    player_ids jsonb not null,
    result text not null,
    ranked boolean not null,
    score_summary jsonb,
    attack_summary jsonb,
    execution_summary jsonb,
    source_version bigint not null,
    projected_at timestamptz not null,
    constraint match_projection_players_are_two_seats
        check (
            jsonb_typeof(player_ids) = 'array'
            and jsonb_array_length(player_ids) = 2
            and jsonb_typeof(player_ids -> 0) = 'string'
            and jsonb_typeof(player_ids -> 1) = 'string'
            and (player_ids ->> 0) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            and (player_ids ->> 1) ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        ),
    constraint match_projection_result_supported
        check (result in ('PLAYER_ONE_WIN', 'PLAYER_TWO_WIN', 'DRAW', 'VOIDED')),
    constraint match_projection_source_version_non_negative check (source_version >= 0)
);

create table post (
    id uuid primary key,
    author_user_id uuid not null,
    visibility text not null,
    prose_markdown text not null,
    match_projection_id uuid references match_projection(match_id),
    audience_circle_id uuid references circle(id),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint post_visibility_supported check (visibility in ('PUBLIC', 'FOLLOWERS', 'CIRCLE', 'PRIVATE')),
    constraint post_circle_visibility_consistent
        check ((visibility = 'CIRCLE') = (audience_circle_id is not null))
);

create index post_author_created_idx on post (author_user_id, created_at desc, id);
create index post_visibility_created_idx on post (visibility, created_at desc, id);

create table comment (
    id uuid primary key,
    post_id uuid not null references post(id),
    author_user_id uuid not null,
    body_markdown text not null,
    created_at timestamptz not null,
    constraint comment_body_not_blank check (btrim(body_markdown) <> '')
);

create index comment_post_created_idx on comment (post_id, created_at, id);

create table post_like (
    post_id uuid not null references post(id),
    user_id uuid not null,
    created_at timestamptz not null,
    primary key (post_id, user_id)
);

create table inbox_event (
    event_id uuid primary key,
    event_type text not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null,
    received_at timestamptz not null,
    applied_at timestamptz,
    constraint inbox_event_aggregate_version_non_negative check (aggregate_version >= 0),
    constraint inbox_event_type_not_blank check (btrim(event_type) <> ''),
    constraint inbox_event_aggregate_version_unique
        unique (aggregate_id, aggregate_version, event_type)
);
