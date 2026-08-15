alter table match
    drop constraint match_resolution_reason_supported,
    add constraint match_resolution_reason_supported check (
        resolution_reason is null
        or resolution_reason in (
            'SURRENDER',
            'DISCONNECT_TIMEOUT',
            'PLATFORM_INCIDENT',
            'JUDGE_RESULT',
            'LEGACY_IMPORT'
        )
    );

alter table match_player
    add column score_problem_version text,
    add column score_runtime_version text,
    add column score_calibration_version text,
    add column placement_count_before integer,
    add column placement_count_after integer,
    add column season_id uuid,
    add column tier_after text,
    add column division_after text;

alter table match_player
    add constraint match_player_versioned_score_consistent check (
        (
            score_problem_version is null
            and score_runtime_version is null
            and score_calibration_version is null
        )
        or (
            score is not null
            and speed_score_component is not null
            and efficiency_score_component is not null
            and submission_score_component is not null
            and score_calculation_version is not null
            and nullif(btrim(score_problem_version), '') is not null
            and nullif(btrim(score_runtime_version), '') is not null
            and nullif(btrim(score_calibration_version), '') is not null
            and score = speed_score_component + efficiency_score_component + submission_score_component
        )
    ),
    add constraint match_player_rating_audit_consistent check (
        (
            placement_count_before is null
            and placement_count_after is null
            and season_id is null
            and tier_after is null
            and division_after is null
        )
        or (
            rating_before is not null
            and rating_after is not null
            and rp_before is not null
            and rp_after is not null
            and placement_count_before >= 0
            and placement_count_after in (placement_count_before, placement_count_before + 1)
            and season_id is not null
            and nullif(btrim(tier_after), '') is not null
        )
    );
