# D³ Working Agreement

Treat the linked issue and requirement IDs in `docs/specs/d3-mvp.md` as the change contract. Keep each change narrow enough to review and demonstrate.

## Work loop

1. Read the issue, affected requirement, nearby code, and relevant pointer below.
2. State the intended acceptance evidence before editing.
3. Preserve service data ownership and public contract compatibility.
4. Run the narrow checks, inspect the diff, and report pass, fail, skip, and not-run separately.

Use English for code, identifiers, comments, schemas, and commit titles. Use Korean for team-facing planning and submission prose unless a surrounding document establishes another convention.

## Boundaries

- Keep identity, battle, judge, and community persistence isolated. Exchange IDs and versioned contracts, never cross-service tables or entities.
- Keep PostgreSQL authoritative. Use Redis only for expiring coordination state.
- Keep user source private by default and user-code execution behind the judge boundary.
- Keep P1 work unavailable behind a feature boundary instead of presenting mock behavior as complete.
- Keep `main` review-only. A commit, push, merge, deployment, GitHub record, Discord webhook, and AWS change each require explicit authority.

## Context pointers

- **Observable behavior:** before changing product behavior or acceptance tests, read `docs/specs/d3-mvp.md`.
- **Frontend:** before changing `apps/web`, read `docs/wireframes/README.md`; styled work starts only from a reviewed wireframe ID.
- **Services and data:** before changing a service boundary, database, HTTP call, or event, read `docs/architecture/services.md`, `docs/architecture/postgresql.md`, and `docs/architecture/erd.dbml`.
- **Security:** before changing auth, WebSocket access, Judge0, secrets, containers, or cloud permissions, read `docs/quality/security-review.md`.
- **Delivery:** before changing Compose or cloud files, read `docs/operations/deployment-plan.md`.

## Project skills

- Invoke `$start-issue` explicitly before beginning assigned issue work.
- Use `$verify-change` before reporting an implementation or fix complete.
- Invoke `$handoff` explicitly when transferring unfinished work.
- Invoke `$demo-smoke` explicitly before a rehearsal or presentation.
- Use `$spring-service` for Spring service, persistence, integration, configuration, and service-test changes.
- Use `$realtime-battle` for matchmaking, match state, WebSocket, reconnect, scoring, rating, and attack changes.
- Use `$judge0-boundary` for run, submit, evaluation, runtime, isolation, and Judge0 adapter changes.

The canonical skill source is `.agents/skills`. `.claude/skills` contains thin adapters and must not duplicate workflow bodies.

## Automation guardrails

- Open pull requests as Ready for review by default so configured CI and bot reviews start. Use Draft only when explicitly requested or when intentionally incomplete work must stay outside normal review; convert it to Ready before requesting review.
- `.codex/hooks.json` and `.claude/settings.json` revalidate shared agent guidance after edits. Review and trust the Codex hook in `/hooks` on first use; never bypass hook trust.
- Lefthook runs scaffold checks before commits and validates Conventional Commit titles. Install it with `pnpm install` or `pnpm hooks:install`.
