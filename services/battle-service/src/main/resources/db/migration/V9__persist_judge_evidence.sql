alter table judge_job_reference
    add column passed_count integer,
    add column total_count integer,
    add column runtime_measurements jsonb,
    add column adapter_version text,
    add column runtime_version text;

alter table judge_job_reference
    add constraint judge_job_reference_safe_evidence_consistent check (
        (
            passed_count is null
            and total_count is null
            and runtime_measurements is null
            and adapter_version is null
            and runtime_version is null
        )
        or (
            passed_count >= 0
            and total_count >= passed_count
            and runtime_measurements is not null
            and adapter_version is not null
            and runtime_version is not null
            and jsonb_typeof(runtime_measurements) = 'array'
            and btrim(adapter_version) <> ''
            and btrim(runtime_version) <> ''
        )
    );

create index inbox_event_pending_submission_judged_idx
    on inbox_event (received_at, event_id)
    where applied_at is null and event_type = 'submission.judged';
