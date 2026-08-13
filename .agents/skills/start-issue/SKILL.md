---
name: start-issue
description: Prepare a D³ issue for implementation by resolving its contract, dependencies, branch, and evidence plan. Use only when the user explicitly invokes $start-issue for assigned work.
---

# Start issue

1. Read the issue and every referenced requirement or artifact. Stop if the issue identity, owner, acceptance evidence, or blocking dependency is unresolved.
2. Inspect the worktree and current branch. Preserve existing work and report overlap before changing it.
3. Restate the bounded contract: outcome, requirement IDs, in-scope files, non-goals, dependencies, and checks.
4. Create `<type>/<issue-number>-<short-slug>` only when branch creation is authorized. Never commit or push as part of this workflow.
5. Finish when the issue, branch, file boundary, and first red or skipped acceptance test are explicit.

