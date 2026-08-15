create table refresh_session_legacy_normalization (
    session_id uuid primary key,
    user_id uuid not null,
    rotated_from_id uuid,
    revoked_at timestamptz,
    canonical_child_session_id uuid,
    normalization_reasons text[] not null,
    archived_at timestamptz not null default now(),
    constraint refresh_session_legacy_reasons_not_empty
        check (cardinality(normalization_reasons) > 0)
);

with inspected as (
    select session.*,
           parent.user_id as parent_user_id,
           first_value(session.id) over (
               partition by session.rotated_from_id
               order by case
                            when session.rotated_from_id is not null
                             and session.id <> session.rotated_from_id
                             and parent.user_id = session.user_id then 0
                            else 1
                        end,
                        session.created_at asc,
                        session.id asc
           ) as canonical_child_session_id,
           row_number() over (
               partition by session.rotated_from_id
               order by case
                            when session.rotated_from_id is not null
                             and session.id <> session.rotated_from_id
                             and parent.user_id = session.user_id then 0
                            else 1
                        end,
                        session.created_at asc,
                        session.id asc
           ) as lineage_rank
    from refresh_session session
    left join refresh_session parent on parent.id = session.rotated_from_id
), classified as (
    select inspected.*,
           array_remove(array[
               case when rotated_from_id = id then 'SELF_ROTATION' end,
               case when rotated_from_id is not null and parent_user_id <> user_id
                    then 'CROSS_USER_ROTATION' end,
               case when rotated_from_id is not null
                          and rotated_from_id <> id
                          and parent_user_id = user_id
                          and lineage_rank > 1
                    then 'DUPLICATE_ROTATION_CHILD' end,
               case when revoked_at < created_at then 'REVOCATION_BEFORE_CREATION' end
           ], null) as normalization_reasons
    from inspected
)
insert into refresh_session_legacy_normalization (
    session_id, user_id, rotated_from_id, revoked_at,
    canonical_child_session_id, normalization_reasons
)
select id,
       user_id,
       rotated_from_id,
       revoked_at,
       case when 'DUPLICATE_ROTATION_CHILD' = any(normalization_reasons)
            then canonical_child_session_id end,
       normalization_reasons
from classified
where cardinality(normalization_reasons) > 0;

update refresh_session session
set rotated_from_id = case
        when normalization.normalization_reasons
             && array['SELF_ROTATION', 'CROSS_USER_ROTATION', 'DUPLICATE_ROTATION_CHILD']
            then null
        else session.rotated_from_id
    end,
    revoked_at = case
        when normalization.normalization_reasons
             && array['SELF_ROTATION', 'CROSS_USER_ROTATION', 'DUPLICATE_ROTATION_CHILD']
            then greatest(session.created_at, coalesce(session.revoked_at, now()))
        when 'REVOCATION_BEFORE_CREATION' = any(normalization.normalization_reasons)
            then session.created_at
        else session.revoked_at
    end
from refresh_session_legacy_normalization normalization
where session.id = normalization.session_id;

alter table refresh_session
    drop constraint refresh_session_rotated_from_id_fkey,
    add constraint refresh_session_identity_unique unique (id, user_id),
    add constraint refresh_session_rotated_parent_unique unique (rotated_from_id),
    add constraint refresh_session_not_self_rotated
        check (rotated_from_id is null or rotated_from_id <> id),
    add constraint refresh_session_revocation_after_creation
        check (revoked_at is null or revoked_at >= created_at),
    add constraint refresh_session_parent_same_user_fk
        foreign key (rotated_from_id, user_id) references refresh_session(id, user_id);
