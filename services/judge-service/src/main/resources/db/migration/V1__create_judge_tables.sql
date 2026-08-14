create table submission (
    id uuid primary key,
    idempotency_key uuid not null unique,
    user_id uuid not null,
    match_id uuid,
    problem_id uuid not null,
    problem_version integer not null check (problem_version > 0),
    mode text not null check (mode in ('RUN', 'SUBMIT')),
    language_key text not null check (language_key in ('C', 'CPP', 'JAVA', 'PYTHON3', 'JAVASCRIPT', 'TYPESCRIPT')),
    source_code text not null,
    attempt_number integer,
    correlation_id text not null check (length(correlation_id) between 1 and 128),
    request_fingerprint text not null,
    status text not null check (status in (
        'QUEUED', 'RUNNING', 'ACCEPTED', 'WRONG_ANSWER', 'COMPILATION_ERROR',
        'RUNTIME_ERROR', 'TIME_LIMIT', 'MEMORY_LIMIT', 'PLATFORM_FAILURE'
    )),
    evaluation_claim_id uuid,
    claim_started_at timestamptz,
    accepted_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint submission_attempt_mode check (
        (mode = 'RUN' and attempt_number is null)
        or (mode = 'SUBMIT' and attempt_number > 0)
    ),
    constraint submission_claim_state check (
        (status = 'RUNNING' and evaluation_claim_id is not null and claim_started_at is not null)
        or (status <> 'RUNNING' and evaluation_claim_id is null and claim_started_at is null)
    )
);

create index submission_pending_evaluation_idx
    on submission (accepted_at, id)
    where status in ('QUEUED', 'RUNNING');

create index submission_match_user_attempt_idx
    on submission (match_id, user_id, attempt_number)
    where match_id is not null;
create index submission_user_created_idx on submission (user_id, created_at desc);

create table judge_run (
    id uuid primary key,
    submission_id uuid not null unique references submission (id),
    adapter_version text not null,
    runtime_version text not null,
    status text not null check (status in (
        'ACCEPTED', 'WRONG_ANSWER', 'COMPILATION_ERROR', 'RUNTIME_ERROR',
        'TIME_LIMIT', 'MEMORY_LIMIT', 'PLATFORM_FAILURE'
    )),
    passed_count integer not null check (passed_count >= 0),
    total_count integer not null check (total_count >= passed_count),
    completed_at timestamptz not null,
    correlation_id text not null
);

create table evaluation_evidence (
    id uuid primary key,
    judge_run_id uuid not null references judge_run (id),
    tier text not null check (tier in ('SMALL', 'MEDIUM', 'LARGE')),
    input_size bigint not null check (input_size > 0),
    sample_count integer not null check (sample_count > 0),
    median_runtime_micros bigint not null check (median_runtime_micros >= 0),
    created_at timestamptz not null
);

create index evaluation_evidence_run_idx on evaluation_evidence (judge_run_id, tier);

create table outbox_event (
    id uuid primary key,
    aggregate_id uuid not null,
    aggregate_version bigint not null,
    event_type text not null,
    payload jsonb not null,
    occurred_at timestamptz not null,
    published_at timestamptz,
    unique (aggregate_id, aggregate_version, event_type)
);

create index outbox_event_unpublished_idx
    on outbox_event (occurred_at, id)
    where published_at is null;
