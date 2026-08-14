alter table match
    drop constraint match_resolution_reason_supported,
    add constraint match_resolution_reason_supported check (
        resolution_reason is null
        or resolution_reason in (
            'SURRENDER',
            'DISCONNECT_TIMEOUT',
            'PLATFORM_INCIDENT',
            'LEGACY_IMPORT'
        )
    );

update match
set void_reason = 'legacy-import'
where result = 'VOIDED'
  and nullif(btrim(void_reason), '') is null;

update match
set resolution_reason = case
        when result = 'VOIDED' then 'PLATFORM_INCIDENT'
        else 'LEGACY_IMPORT'
    end
where status = 'FINISHED'
  and result is not null
  and resolution_reason is null;

alter table match
    add constraint match_terminal_resolution_reason_consistent check (
        (status = 'FINISHED') = (resolution_reason is not null)
    ),
    add constraint match_void_reason_required check (
        (result = 'VOIDED') = (nullif(btrim(void_reason), '') is not null)
    );
