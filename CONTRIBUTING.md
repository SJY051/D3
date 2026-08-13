# Contributing to D³

## Issue first

Every change starts from an assigned issue with requirement IDs and observable acceptance evidence. Use the repository issue forms once the GitHub repository exists.

## Branches and titles

- Branch: `<type>/<issue-number>-<short-slug>`
- Types: `feat`, `fix`, `docs`, `test`, `refactor`, `build`, `ci`, `chore`
- PR and commit title: English Conventional Commit, for example `feat(battle): add authoritative match clock`

Do not make direct changes on `main`. Keep one concern per pull request and link the issue with `Closes #<number>` when the PR fully resolves it.

## Pull request evidence

Include:

- requirement IDs and user-visible outcome;
- schema or compatibility impact;
- exact commands and pass/fail/skip counts;
- screenshots or recordings for visual changes;
- risks, deferred work, and rollback notes.

Codex review supplements one human approval. Resolve review conversations and required checks before squash merge.

