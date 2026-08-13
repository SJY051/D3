# Collaboration activation

## GitHub workflow

1. Create and assign an issue from an issue form before implementation.
2. Link one or more IDs from `docs/specs/d3-mvp.md` and state observable evidence.
3. Work on `<type>/<issue-number>-<short-slug>` and open a small pull request.
4. Require CI, the GitHub Codex review, and one human approval before squash merge.

Suggested labels are `area:identity`, `area:battle`, `area:judge`, `area:community`, `area:web`, `area:platform`, `type:feature`, `type:bug`, `type:task`, `priority:p0`, and `priority:p1`. Labels, repository rules, assignees, and milestones are external records and must be created only after the repository exists and the team confirms ownership.

## Discord visibility

Connect the GitHub repository to the team Discord channel after the channel and webhook secret are assigned. Subscribe to issue, pull request, review, check-suite, and deployment events. Store the webhook only in GitHub encrypted secrets; never in `.env`, source, logs, or screenshots.

The webhook is visibility support, not evidence that a task is complete. GitHub issue/PR state and CI remain authoritative.
