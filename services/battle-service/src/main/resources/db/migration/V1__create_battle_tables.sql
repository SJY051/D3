create table problem (
    id uuid primary key,
    slug text not null unique,
    version integer not null,
    title text not null,
    difficulty text not null,
    expected_complexity text,
    active boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint problem_slug_not_blank check (btrim(slug) <> ''),
    constraint problem_version_positive check (version > 0),
    constraint problem_title_not_blank check (btrim(title) <> '')
);

create table match (
    id uuid primary key,
    problem_id uuid not null references problem(id),
    ranked boolean not null,
    status text not null,
    result text,
    server_started_at timestamptz,
    deadline_at timestamptz,
    finished_at timestamptz,
    void_reason text,
    aggregate_version bigint not null default 0,
    created_at timestamptz not null,
    constraint match_status_supported
        check (status in ('LOBBY', 'READY', 'RUNNING', 'JUDGING', 'FINISHED')),
    constraint match_result_supported
        check (result is null or result in ('PLAYER_ONE_WIN', 'PLAYER_TWO_WIN', 'DRAW', 'VOIDED')),
    constraint match_terminal_result_consistent check ((status = 'FINISHED') = (result is not null)),
    constraint match_deadline_after_start check (
        deadline_at is null or (server_started_at is not null and deadline_at > server_started_at)
    ),
    constraint match_finish_after_creation check (finished_at is null or finished_at >= created_at),
    constraint match_void_reason_consistent check (result = 'VOIDED' or void_reason is null),
    constraint match_aggregate_version_non_negative check (aggregate_version >= 0)
);

create table match_player (
    match_id uuid not null references match(id),
    user_id uuid not null,
    seat smallint not null,
    language_key text not null,
    connection_state text not null,
    reconnect_deadline_at timestamptz,
    accepted_submission_id uuid,
    attempts integer not null default 0,
    score numeric,
    speed_score_component numeric,
    efficiency_score_component numeric,
    submission_score_component numeric,
    score_calculation_version text,
    rating_before integer,
    rating_after integer,
    rp_before integer,
    rp_after integer,
    primary key (match_id, user_id),
    constraint match_player_supported_seat check (seat in (1, 2)),
    constraint match_player_seat_unique unique (match_id, seat),
    constraint match_player_language_not_blank check (btrim(language_key) <> ''),
    constraint match_player_connection_state_supported
        check (connection_state in ('CONNECTED', 'DISCONNECTED')),
    constraint match_player_attempts_non_negative check (attempts >= 0)
);

create index match_player_user_match_idx on match_player (user_id, match_id);

create table judge_job_reference (
    submission_id uuid primary key,
    match_id uuid not null references match(id),
    player_user_id uuid not null,
    mode text not null,
    command_id uuid not null unique,
    attempt_number integer not null,
    last_judge_status text not null,
    evidence_version text,
    accepted_at timestamptz not null,
    last_result_at timestamptz,
    constraint judge_job_reference_mode_supported check (mode in ('RUN', 'SUBMIT')),
    constraint judge_job_reference_attempt_non_negative check (attempt_number >= 0),
    constraint judge_job_reference_status_supported check (
        last_judge_status in (
            'QUEUED',
            'RUNNING',
            'ACCEPTED',
            'WRONG_ANSWER',
            'COMPILATION_ERROR',
            'RUNTIME_ERROR',
            'TIME_LIMIT',
            'MEMORY_LIMIT',
            'PLATFORM_FAILURE'
        )
    )
);

create index judge_job_reference_match_player_attempt_idx
    on judge_job_reference (match_id, player_user_id, mode, attempt_number);

create table attack_event (
    id uuid primary key,
    match_id uuid not null references match(id),
    sequence bigint not null,
    actor_user_id uuid not null,
    target_user_id uuid not null,
    attack_type text not null,
    resolution text not null,
    energy_cost integer not null,
    occurred_at timestamptz not null,
    constraint attack_event_sequence_unique unique (match_id, sequence),
    constraint attack_event_sequence_positive check (sequence > 0),
    constraint attack_event_distinct_players check (actor_user_id <> target_user_id),
    constraint attack_event_energy_non_negative check (energy_cost >= 0)
);

create table rating (
    user_id uuid primary key,
    public_rating integer not null,
    placement_count integer not null default 0,
    games_played integer not null default 0,
    updated_at timestamptz not null,
    constraint rating_placement_count_non_negative check (placement_count >= 0),
    constraint rating_games_played_non_negative check (games_played >= 0)
);

create table season_rank (
    season_id uuid not null,
    user_id uuid not null,
    rp integer not null,
    tier text not null,
    division text,
    peak_tier text not null,
    updated_at timestamptz not null,
    primary key (season_id, user_id),
    constraint season_rank_tier_not_blank check (btrim(tier) <> ''),
    constraint season_rank_peak_tier_not_blank check (btrim(peak_tier) <> '')
);

create index season_rank_season_rp_idx on season_rank (season_id, rp desc, user_id);

create table outbox_event (
    id uuid primary key,
    aggregate_id uuid not null,
    aggregate_version bigint not null,
    event_type text not null,
    payload jsonb not null,
    occurred_at timestamptz not null,
    published_at timestamptz,
    constraint outbox_event_aggregate_version_non_negative check (aggregate_version >= 0),
    constraint outbox_event_type_not_blank check (btrim(event_type) <> ''),
    constraint outbox_event_aggregate_version_unique
        unique (aggregate_id, aggregate_version, event_type)
);

create index outbox_event_unpublished_idx
    on outbox_event (occurred_at, id)
    where published_at is null;

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
