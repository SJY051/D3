do $$
begin
    if exists (
        select 1
        from match_player player
        where player.accepted_submission_id is not null
          and not exists (
              select 1
              from judge_job_reference job
              where job.submission_id = player.accepted_submission_id
                and job.match_id = player.match_id
                and job.player_user_id = player.user_id
                and job.mode = 'SUBMIT'
                and job.last_judge_status = 'ACCEPTED'
          )
    ) then
        raise exception 'accepted_submission_id must identify the same player accepted SUBMIT before migration';
    end if;
end
$$;

alter table match
    add constraint match_terminal_finish_time_consistent
        check ((status = 'FINISHED') = (finished_at is not null)),
    add constraint match_clock_state_consistent check (
        (status in ('LOBBY', 'READY') and server_started_at is null and deadline_at is null)
        or (status in ('RUNNING', 'JUDGING', 'FINISHED')
            and server_started_at is not null and deadline_at is not null)
    ),
    add constraint match_start_after_creation
        check (server_started_at is null or server_started_at >= created_at),
    drop constraint match_finish_after_creation,
    add constraint match_finish_after_creation check (
        finished_at is null
        or (
            finished_at >= created_at
            and (server_started_at is null or finished_at >= server_started_at)
        )
    ),
    drop constraint match_void_reason_consistent,
    add constraint match_void_reason_consistent check (
        (result is not distinct from 'VOIDED' and nullif(btrim(void_reason), '') is not null)
        or (result is distinct from 'VOIDED' and void_reason is null)
    );

alter table match_player
    add constraint match_player_reconnect_deadline_consistent check (
        (connection_state = 'DISCONNECTED') = (reconnect_deadline_at is not null)
    );

alter table judge_job_reference
    alter column attempt_number drop not null;

update judge_job_reference
set attempt_number = null
where mode = 'RUN';

alter table judge_job_reference
    drop constraint judge_job_reference_attempt_non_negative,
    add constraint judge_job_reference_player_fk
        foreign key (match_id, player_user_id) references match_player(match_id, user_id),
    add constraint judge_job_reference_attempt_matches_mode check (
        (mode = 'RUN' and attempt_number is null)
        or (mode = 'SUBMIT' and attempt_number is not null and attempt_number > 0)
    ),
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
    ),
    add constraint judge_job_reference_result_after_acceptance
        check (last_result_at is null or last_result_at >= accepted_at);

create unique index judge_job_reference_submit_attempt_unique_idx
    on judge_job_reference (match_id, player_user_id, attempt_number)
    where mode = 'SUBMIT';

create unique index judge_job_reference_one_accepted_submit_idx
    on judge_job_reference (match_id, player_user_id)
    where mode = 'SUBMIT' and last_judge_status = 'ACCEPTED';

alter table attack_event
    add constraint attack_event_actor_fk
        foreign key (match_id, actor_user_id) references match_player(match_id, user_id),
    add constraint attack_event_target_fk
        foreign key (match_id, target_user_id) references match_player(match_id, user_id);

alter table match_player
    drop column accepted_submission_id;
