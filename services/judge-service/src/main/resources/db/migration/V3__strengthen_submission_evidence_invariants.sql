create table submission_legacy_attempt_normalization (
    submission_id uuid primary key references submission (id),
    legacy_attempt_number integer,
    normalized_attempt_number integer not null,
    normalized_at timestamptz not null default now(),
    constraint submission_legacy_attempt_was_null
        check (legacy_attempt_number is null),
    constraint submission_normalized_attempt_positive
        check (normalized_attempt_number > 0)
);

with invalid_attempts as (
    select candidate.id,
           coalesce((
               select max(existing.attempt_number)
               from submission existing
               where existing.mode = 'SUBMIT'
                 and existing.attempt_number > 0
                 and existing.user_id = candidate.user_id
                 and existing.match_id is not distinct from candidate.match_id
                 and existing.problem_id = candidate.problem_id
           ), 0) + row_number() over (
               partition by candidate.user_id, candidate.match_id, candidate.problem_id
               order by candidate.accepted_at asc, candidate.id asc
           ) as normalized_attempt_number
    from submission candidate
    where candidate.mode = 'SUBMIT'
      and candidate.attempt_number is null
)
insert into submission_legacy_attempt_normalization (
    submission_id, legacy_attempt_number, normalized_attempt_number
)
select id, null, normalized_attempt_number
from invalid_attempts;

update submission candidate
set attempt_number = normalization.normalized_attempt_number
from submission_legacy_attempt_normalization normalization
where candidate.id = normalization.submission_id;

alter table submission
    drop constraint submission_attempt_mode,
    add constraint submission_attempt_mode check (
        (mode = 'RUN' and attempt_number is null)
        or (mode = 'SUBMIT' and attempt_number is not null and attempt_number > 0)
    );

drop index evaluation_evidence_run_idx;

-- V1 allowed more than one measurement for the same run and size tier. Keep the
-- first observation active and archive every other raw observation before
-- enforcing the one-row-per-tier invariant. Never synthesize a median from
-- medians because the original runtime samples are not available here.
create table evaluation_evidence_legacy_duplicate (
    id uuid primary key,
    judge_run_id uuid not null references judge_run (id),
    tier text not null,
    input_size bigint not null,
    sample_count integer not null,
    median_runtime_micros bigint not null,
    created_at timestamptz not null,
    canonical_evidence_id uuid not null references evaluation_evidence (id),
    archived_at timestamptz not null default now(),
    constraint evaluation_evidence_legacy_distinct_ids check (id <> canonical_evidence_id)
);

with ranked_evidence as (
    select evidence.*,
           first_value(id) over (
               partition by judge_run_id, tier
               order by created_at asc, id asc
           ) as canonical_evidence_id,
           row_number() over (
               partition by judge_run_id, tier
               order by created_at asc, id asc
           ) as evidence_rank
    from evaluation_evidence evidence
)
insert into evaluation_evidence_legacy_duplicate (
    id, judge_run_id, tier, input_size, sample_count,
    median_runtime_micros, created_at, canonical_evidence_id
)
select id, judge_run_id, tier, input_size, sample_count,
       median_runtime_micros, created_at, canonical_evidence_id
from ranked_evidence
where evidence_rank > 1;

delete from evaluation_evidence evidence
using evaluation_evidence_legacy_duplicate legacy
where evidence.id = legacy.id;

create unique index evaluation_evidence_run_tier_unique_idx
    on evaluation_evidence (judge_run_id, tier);
