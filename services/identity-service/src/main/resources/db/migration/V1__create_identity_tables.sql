create table user_account (
    id uuid primary key,
    handle text not null unique,
    email text not null unique,
    password_hash text,
    display_name text not null,
    bio text,
    status text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint user_account_handle_not_blank check (btrim(handle) <> ''),
    constraint user_account_email_not_blank check (btrim(email) <> ''),
    constraint user_account_display_name_not_blank check (btrim(display_name) <> '')
);

create table login_identity (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    provider text not null,
    provider_subject text not null,
    created_at timestamptz not null,
    constraint login_identity_provider_supported check (provider in ('PASSWORD', 'GITHUB')),
    constraint login_identity_provider_subject_not_blank check (btrim(provider_subject) <> ''),
    constraint login_identity_provider_subject_unique unique (provider, provider_subject)
);

create index login_identity_user_id_idx on login_identity (user_id);

create table refresh_session (
    id uuid primary key,
    user_id uuid not null references user_account(id),
    token_hash text not null unique,
    expires_at timestamptz not null,
    rotated_from_id uuid references refresh_session(id),
    revoked_at timestamptz,
    created_at timestamptz not null,
    constraint refresh_session_token_hash_not_blank check (btrim(token_hash) <> ''),
    constraint refresh_session_expiry_after_creation check (expires_at > created_at)
);

create index refresh_session_user_expiry_idx on refresh_session (user_id, expires_at);

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
