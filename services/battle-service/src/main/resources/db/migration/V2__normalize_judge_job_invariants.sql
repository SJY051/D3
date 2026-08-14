create table match_player_legacy_accepted_pointer (
    match_id uuid not null,
    player_user_id uuid not null,
    accepted_submission_id uuid not null,
    correlation_valid boolean not null,
    archived_at timestamptz not null default now(),
    primary key (match_id, player_user_id)
);

insert into match_player_legacy_accepted_pointer (
    match_id, player_user_id, accepted_submission_id, correlation_valid
)
select player.match_id,
       player.user_id,
       player.accepted_submission_id,
       exists (
           select 1
           from judge_job_reference job
           where job.submission_id = player.accepted_submission_id
             and job.match_id = player.match_id
             and job.player_user_id = player.user_id
             and job.mode = 'SUBMIT'
             and job.last_judge_status = 'ACCEPTED'
       )
from match_player player
where player.accepted_submission_id is not null;

create table judge_job_reference_legacy_duplicate (
    submission_id uuid primary key,
    match_id uuid not null,
    player_user_id uuid not null,
    mode text not null,
    command_id uuid not null,
    attempt_number integer,
    last_judge_status text not null,
    evidence_version text,
    accepted_at timestamptz not null,
    last_result_at timestamptz,
    canonical_submission_id uuid not null,
    archive_reason text not null,
    archived_at timestamptz not null default now(),
    constraint judge_job_reference_legacy_distinct_ids
        check (submission_id <> canonical_submission_id),
    constraint judge_job_reference_legacy_reason_supported
        check (archive_reason in ('DUPLICATE_SUBMIT_ATTEMPT', 'DUPLICATE_ACCEPTED_SUBMIT'))
);

-- Preserve one logical result per SUBMIT attempt. Prefer the legacy accepted
-- pointer only when it correlated to an ACCEPTED SUBMIT, then prefer an
-- ACCEPTED result and the earliest accepted command.
with ranked_attempts as (
    select job.*,
           first_value(job.submission_id) over (
               partition by job.match_id, job.player_user_id, job.attempt_number
               order by case
                            when pointer.correlation_valid
                             and job.submission_id = pointer.accepted_submission_id then 0
                            else 1
                        end,
                        case when job.last_judge_status = 'ACCEPTED' then 0 else 1 end,
                        job.accepted_at asc,
                        job.submission_id asc
           ) as canonical_submission_id,
           row_number() over (
               partition by job.match_id, job.player_user_id, job.attempt_number
               order by case
                            when pointer.correlation_valid
                             and job.submission_id = pointer.accepted_submission_id then 0
                            else 1
                        end,
                        case when job.last_judge_status = 'ACCEPTED' then 0 else 1 end,
                        job.accepted_at asc,
                        job.submission_id asc
           ) as submission_rank
    from judge_job_reference job
    left join match_player_legacy_accepted_pointer pointer
      on pointer.match_id = job.match_id
     and pointer.player_user_id = job.player_user_id
    where job.mode = 'SUBMIT'
)
insert into judge_job_reference_legacy_duplicate (
    submission_id, match_id, player_user_id, mode, command_id,
    attempt_number, last_judge_status, evidence_version, accepted_at,
    last_result_at, canonical_submission_id, archive_reason
)
select submission_id, match_id, player_user_id, mode, command_id,
       attempt_number, last_judge_status, evidence_version, accepted_at,
       last_result_at, canonical_submission_id, 'DUPLICATE_SUBMIT_ATTEMPT'
from ranked_attempts
where submission_rank > 1;

delete from judge_job_reference job
using judge_job_reference_legacy_duplicate legacy
where job.submission_id = legacy.submission_id;

-- V1 also allowed more than one accepted attempt. Preserve a valid pointer
-- target when present; otherwise the lowest successful attempt is canonical.
with ranked_accepts as (
    select job.*,
           first_value(job.submission_id) over (
               partition by job.match_id, job.player_user_id
               order by case
                            when pointer.correlation_valid
                             and job.submission_id = pointer.accepted_submission_id then 0
                            else 1
                        end,
                        job.attempt_number asc,
                        job.accepted_at asc,
                        job.submission_id asc
           ) as canonical_submission_id,
           row_number() over (
               partition by job.match_id, job.player_user_id
               order by case
                            when pointer.correlation_valid
                             and job.submission_id = pointer.accepted_submission_id then 0
                            else 1
                        end,
                        job.attempt_number asc,
                        job.accepted_at asc,
                        job.submission_id asc
           ) as submission_rank
    from judge_job_reference job
    left join match_player_legacy_accepted_pointer pointer
      on pointer.match_id = job.match_id
     and pointer.player_user_id = job.player_user_id
    where job.mode = 'SUBMIT'
      and job.last_judge_status = 'ACCEPTED'
)
insert into judge_job_reference_legacy_duplicate (
    submission_id, match_id, player_user_id, mode, command_id,
    attempt_number, last_judge_status, evidence_version, accepted_at,
    last_result_at, canonical_submission_id, archive_reason
)
select submission_id, match_id, player_user_id, mode, command_id,
       attempt_number, last_judge_status, evidence_version, accepted_at,
       last_result_at, canonical_submission_id, 'DUPLICATE_ACCEPTED_SUBMIT'
from ranked_accepts
where submission_rank > 1;

delete from judge_job_reference job
using judge_job_reference_legacy_duplicate legacy
where job.submission_id = legacy.submission_id;

alter table match
    add constraint match_terminal_finish_time_consistent
        check ((status = 'FINISHED') = (finished_at is not null)) not valid,
    add constraint match_clock_state_consistent check (
        (status in ('LOBBY', 'READY') and server_started_at is null and deadline_at is null)
        or (status in ('RUNNING', 'JUDGING', 'FINISHED')
            and server_started_at is not null and deadline_at is not null)
    ) not valid,
    add constraint match_start_after_creation
        check (server_started_at is null or server_started_at >= created_at) not valid,
    drop constraint match_finish_after_creation,
    add constraint match_finish_after_creation check (
        finished_at is null
        or (
            finished_at >= created_at
            and (server_started_at is null or finished_at >= server_started_at)
        )
    ) not valid,
    drop constraint match_void_reason_consistent,
    add constraint match_void_reason_consistent check (
        (result is not distinct from 'VOIDED' and nullif(btrim(void_reason), '') is not null)
        or (result is distinct from 'VOIDED' and void_reason is null)
    ) not valid;

alter table match_player
    add constraint match_player_reconnect_deadline_consistent check (
        (connection_state = 'DISCONNECTED') = (reconnect_deadline_at is not null)
    ) not valid;

alter table judge_job_reference
    alter column attempt_number drop not null;

update judge_job_reference
set attempt_number = null
where mode = 'RUN';

alter table judge_job_reference
    drop constraint judge_job_reference_attempt_non_negative,
    add constraint judge_job_reference_player_fk
        foreign key (match_id, player_user_id) references match_player(match_id, user_id) not valid,
    add constraint judge_job_reference_attempt_matches_mode check (
        (mode = 'RUN' and attempt_number is null)
        or (mode = 'SUBMIT' and attempt_number is not null and attempt_number > 0)
    ) not valid,
    add constraint judge_job_reference_result_state_consistent check (
        (
            last_judge_status in ('QUEUED', 'RUNNING')
            and evidence_version is null
            and last_result_at is null
        )
        or (
            last_judge_status in (
                'ACCEPTED',
                'WRONG_ANSWER',
                'COMPILATION_ERROR',
                'RUNTIME_ERROR',
                'TIME_LIMIT',
                'MEMORY_LIMIT',
                'PLATFORM_FAILURE'
            )
            and nullif(btrim(evidence_version), '') is not null
            and last_result_at is not null
        )
    ) not valid,
    add constraint judge_job_reference_result_after_acceptance
        check (last_result_at is null or last_result_at >= accepted_at) not valid;

create unique index judge_job_reference_submit_attempt_unique_idx
    on judge_job_reference (match_id, player_user_id, attempt_number)
    where mode = 'SUBMIT';

create unique index judge_job_reference_one_accepted_submit_idx
    on judge_job_reference (match_id, player_user_id)
    where mode = 'SUBMIT' and last_judge_status = 'ACCEPTED';

alter table attack_event
    add constraint attack_event_actor_fk
        foreign key (match_id, actor_user_id) references match_player(match_id, user_id) not valid,
    add constraint attack_event_target_fk
        foreign key (match_id, target_user_id) references match_player(match_id, user_id) not valid;

alter table match_player
    drop column accepted_submission_id;
