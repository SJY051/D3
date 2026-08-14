alter table submission
    drop constraint submission_attempt_mode,
    add constraint submission_attempt_mode check (
        (mode = 'RUN' and attempt_number is null)
        or (mode = 'SUBMIT' and attempt_number is not null and attempt_number > 0)
    );

drop index evaluation_evidence_run_idx;

-- V1 allowed more than one measurement for the same run and size tier. Keep the
-- strongest observed measurement as the deterministic representative before
-- enforcing the one-row-per-tier invariant. Never synthesize a median from
-- medians because the original runtime samples are not available here.
with ranked_evidence as (
    select id,
           row_number() over (
               partition by judge_run_id, tier
               order by sample_count desc,
                        input_size desc,
                        created_at desc,
                        id asc
           ) as evidence_rank
    from evaluation_evidence
)
delete from evaluation_evidence evidence
using ranked_evidence ranked
where evidence.id = ranked.id
  and ranked.evidence_rank > 1;

create unique index evaluation_evidence_run_tier_unique_idx
    on evaluation_evidence (judge_run_id, tier);
