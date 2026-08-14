alter table submission
    drop constraint submission_attempt_mode,
    add constraint submission_attempt_mode check (
        (mode = 'RUN' and attempt_number is null)
        or (mode = 'SUBMIT' and attempt_number is not null and attempt_number > 0)
    );

drop index evaluation_evidence_run_idx;

create unique index evaluation_evidence_run_tier_unique_idx
    on evaluation_evidence (judge_run_id, tier);
