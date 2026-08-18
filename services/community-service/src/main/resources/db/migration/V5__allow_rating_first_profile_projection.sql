-- rating.changed.v1 (Battle) and user-profile.changed.v1 (Identity) each upsert the same
-- profile_projection row, each owning its own columns and source version. Delivery order between the
-- two producers is not guaranteed, so a rating may arrive before the identity projection creates the
-- row. Relax the identity-owned columns to nullable so a rating-first upsert can persist rating/RP/tier
-- and the later identity projection fills handle/identity_source_version.
alter table profile_projection
    alter column handle drop not null,
    alter column identity_source_version drop not null;

alter table profile_projection
    drop constraint profile_projection_handle_not_blank,
    add constraint profile_projection_handle_not_blank
        check (handle is null or btrim(handle) <> ''),
    drop constraint profile_projection_identity_version_non_negative,
    add constraint profile_projection_identity_version_non_negative
        check (identity_source_version is null or identity_source_version >= 0);
