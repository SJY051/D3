-- The Judge0 catalog recognizes this exact problem ID at version 1.
-- Retire every other active problem (manual rehearsal rows included) so
-- upgraded databases converge on the reproducible seed the judge can score.
update problem
set active = false,
    updated_at = timestamptz '2026-08-18T00:00:00Z'
where active is true
  and id <> '00000000-0000-4000-8000-000000000001';

insert into problem (
    id,
    slug,
    version,
    title,
    difficulty,
    expected_complexity,
    active,
    created_at,
    updated_at
) values (
    '00000000-0000-4000-8000-000000000001',
    'demo-sum-v1',
    1,
    'Deterministic demonstration sum',
    'EASY',
    'O(n)',
    true,
    timestamptz '2026-08-18T00:00:00Z',
    timestamptz '2026-08-18T00:00:00Z'
)
on conflict (id) do update
set slug = excluded.slug,
    version = excluded.version,
    title = excluded.title,
    difficulty = excluded.difficulty,
    expected_complexity = excluded.expected_complexity,
    active = excluded.active,
    updated_at = excluded.updated_at;
