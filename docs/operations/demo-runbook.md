# Demo runbook

Owner: 최정민

Status: Two-session local rehearsal passed with one manual problem seed; no-database-edit acceptance remains pending

Last verified: 2026-08-18 on frozen `25359ad` in an isolated `d3checkpoint` Compose project

Requirement: D3-UX-002

Related: [presentation outline](../presentation/outline.md), [test plan](../quality/test-plan.md), [deployment plan](deployment-plan.md)

The checkpoint allocation is 10 minutes of presentation, 5 minutes of live demonstration, and 5 minutes of questions. 최정민 leads; service owners answer technical questions.

## Evidence boundary

The 2026-08-18 rehearsal validates the local two-session path on frozen `25359ad` with the configured fake Judge: register and sign-in, feed publish and cross-session read, ranked matching, Battle v3 Run/Submit and reconnect, autonomous deadline completion, rating projection, automatic result post, result view and player record. It does not validate the deployed judge-service-to-Judge0 private route, attack exchange, a Surrender UI, handle search, application deployment, measured latency or a backup recording. The run required one manual `problem` row because a version-controlled demonstration seed is still missing; therefore Scenario A's “without database edits” acceptance remains **PENDING**. Mark every later run with its revision and capture time, and never replace a failed live step with an unlabeled mock.

## Freeze one demonstrable build

Record these values in the rehearsal issue before the first timed run:

| Field | Required value |
|---|---|
| Git revision | Full commit SHA |
| Branch or tag | Reviewed demo candidate |
| Build time | ISO 8601 with timezone |
| Operator | Name responsible for start/recovery |
| Environment | Local or bound AWS target; exact host profile |
| Judge | Judge0 release, image digests and six runtime versions |
| Seed | Fixture revision and deterministic problem ID |
| Recording | Absolute capture time, revision and storage owner |

Credentials stay outside the repository and recording. Seeded demo identities must use rehearsal-only passwords supplied through the agreed secret handoff.

## Pre-rehearsal gates

| Gate | Owner | Ready when | Current state |
|---|---|---|---|
| Reviewed WF-01~08 | 최정민 | UI review is recorded before styled acceptance | WF-01–06 P0 revision approved 2026-08-16; WF-07/08 review required |
| Scenario A | Service owners | Two browsers complete ranked flow without database edits | PARTIAL PASS: full local two-session path passed on 2026-08-18, but a manual problem seed was required; no-database-edit acceptance PENDING ([Issue #73](https://github.com/SJY051/D3/issues/73)) |
| Scenario B | Battle owners | Reconnect, surrender and timeout loss are deterministic | PARTIAL PASS: reconnect recovery and autonomous deadline completion observed on 2026-08-18; Surrender UI not exposed, timeout-loss path not exercised live |
| Scenario C | Battle/Judge owners | Incident void commits without rating change | NOT RUN live: deterministic domain and void tests exist, but no live platform-failure demonstration has been executed |
| Six language runtimes | Judge owner | Versioned smoke cases pass on designated host | PASS for host: pinned Judge0 CE 1.13.1 matrix and 12/12 language cases |
| Judge application route | 윤서진 | judge-service reaches the designated host through the source-SG-only path and repeats the sanitized six-runtime smoke | NOT RUN: private route and deployment egress PENDING |
| Observability | 윤서진 | Health, correlation and failure views are identified | Local health and ingress correlation PASS; dashboards pending |
| Deployment target | 윤서진 | Environment and rollback owner are bound | AWS `UNKNOWN`; isolated local path rehearsed |
| Backup recording | 최정민 | Same build and real services are visibly labeled | NOT RECORDED: D3-UX-002 requires a clearly identified local backup recording; record the pre-checkpoint run. The screenshot sequence is only an interim degraded fallback and does not satisfy this gate |

All gates required by the chosen live path must be green before announcing demo readiness.

## Start and preflight

On the frozen local environment:

```bash
pnpm install --frozen-lockfile
pnpm verify:scaffold
./gradlew build
docker compose -f infra/compose.yaml config --quiet
COMPOSE_PROJECT_NAME=d3checkpoint pnpm local:start
```

`pnpm local:start` runs `demo:preflight` after all local processes become healthy. Preflight requires Web, discovery, config, gateway, four domain services, PostgreSQL, Redis, Kafka, and the adapter selected for that build. It also waits for the anonymous Gateway route `GET /api/v1/community/matches/00000000-0000-4000-8000-000000000000` to converge to its Community contract response: HTTP `404` with `code=MATCH_RECORD_NOT_FOUND`. Transient network and `5xx` failures are retried for a bounded interval, while another status, a missing contract marker, or retry exhaustion produces `NOT READY`. The route origin is derived from `D3_GATEWAY_HEALTH_URL`; the probe reads only the public error `code`, and output contains the code plus status/attempt metadata but never the full response body or message. The deterministic fake is the 2026-08-18 local rehearsal adapter and must be disclosed as such; real Judge0 remains separate evidence. Archive the terminal output with the revision; a `NOT READY` result blocks the live path and browser launch.

If startup reports a Flyway checksum mismatch from an existing local PostgreSQL history, do not repair or reuse that database. Stop the failed default project without deleting its volumes, then start a fresh named project:

```bash
docker compose -f infra/compose.yaml down
COMPOSE_PROJECT_NAME=d3checkpoint pnpm local:start
```

Keep `COMPOSE_PROJECT_NAME=d3checkpoint` on later Compose commands. This creates project-scoped fresh volumes while preserving the existing local database. After the rehearsal, remove only the isolated project with `COMPOSE_PROJECT_NAME=d3checkpoint docker compose -f infra/compose.yaml down --volumes`.

After preflight is READY, verify that the Battle-owned `problem` table contains exactly one active demonstration problem:

```bash
COMPOSE_PROJECT_NAME=d3checkpoint docker compose -f infra/compose.yaml exec -T postgres \
  psql -U d3_admin -d d3_battle -c \
  "select id, slug, version from problem where active is true order by created_at;"
```

If the fresh database returns no active row, record the deviation and insert the temporary rehearsal seed:

```bash
COMPOSE_PROJECT_NAME=d3checkpoint docker compose -f infra/compose.yaml exec -T postgres \
  psql -U d3_admin -d d3_battle -c \
  "insert into problem (id, slug, version, title, difficulty, active, created_at, updated_at)
   values ('00000000-0000-4000-8000-000000000073', 'checkpoint-demo', 1,
           'Checkpoint demonstration problem', 'EASY', true, now(), now())
   on conflict (slug) do update set active = true, updated_at = excluded.updated_at;"
```

Re-run the query and record the returned ID with the frozen revision. This manual seed is the temporary procedure tracked by [Issue #73](https://github.com/SJY051/D3/issues/73); any run using it must be labeled accordingly and must **not** claim Scenario A's “no manual database edits” acceptance.

Before opening the browser, confirm:

- the `gateway-community-route` preflight record is `ok: true` with `status: 404` and `contractCode: "MATCH_RECORD_NOT_FOUND"`; do not open either browser before this routed gate passes;
- the seed query returns exactly one active demonstration problem and its ID is recorded;
- both sessions are independent and already at the sign-in screen;
- seeded users, fixed ranked problem and deterministic attack configuration match the recorded seed;
- clocks are synchronized enough to interpret server timestamps;
- observability and operator terminals are visible but contain no source, token or hidden test;
- the screenshot sequence or backup recording opens locally and is labeled with its build revision.

## Rehearsal result — 2026-08-18

Frozen `25359ad` passed `pnpm local:start` and `demo-preflight: READY` in fresh `COMPOSE_PROJECT_NAME=d3checkpoint` volumes. The routed Community gate converged to `404/MATCH_RECORD_NOT_FOUND` after 54 attempts in 26.8 seconds.

Two independent browser sessions passed register → sign-in → feed, sanitized fenced-code publish and cross-session read, same-language queue → the same MATCHED battle, Ready → RUNNING, Run energy `0 → 10`, wrong Submit → `WRONG_ANSWER` with the match continuing, accepted Submit → `ACCEPTED` with submission lock, and one WebSocket reconnect. The deadline driver then completed the match as `FINISHED / PLAYER_TWO_WIN / JUDGE_RESULT`; two `rating.changed.v1` events produced rating-first profile rows `1532/+25RP` and `1468/0RP`, followed by the automatic result post, viewer-relative/neutral result labels and player-record lookup.

Explicit skips: attack exchange was not exercised because of its energy requirement; the Battle UI does not expose Surrender even though the `SURRENDER` command exists; handle search and the Identity `user-profile.changed.v1` projection are not implemented. The run used the manual problem seed above, so the no-database-edit acceptance is not claimed. Evidence: [two-session rehearsal](https://github.com/SJY051/D3/issues/19#issuecomment-5322297284) and [checkpoint report/timed path](https://github.com/SJY051/D3/issues/19#issuecomment-5322527613).

## Timed presentation path

| Time | Lead action | Required visible evidence |
|---|---|---|
| 00:00 | Register session 1, sign in and land on Feed | Real Identity session and WF-01 → WF-02 transition |
| 00:40 | Publish a fenced-code post; sign in session 2 and read it | Sanitized rendering, immediate local update and public cross-session read |
| 01:30 | Join the same-language ranked queue in both sessions | Both tickets reach MATCHED and navigate to the same battle |
| 02:20 | Ready, Run, submit one wrong answer, then the accepted answer | RUNNING, energy gain, `WRONG_ANSWER` continuation, `ACCEPTED` and submission lock |
| 03:30 | Jump to a pre-finished match to avoid the ten-minute deadline | Automatic result post, result page and player record |
| 04:30 | Close on rating projection | Record shows the rehearsed rating/RP outcome |

Do not attempt attack exchange, Surrender or handle search during this five-minute path. If a live step fails, say “Switching to the frozen `25359ad` rehearsal screenshot sequence,” distinguish screenshots from live output on screen and verbally, and continue from the corresponding timestamp. Never describe the screenshot sequence or fake Judge as live production evidence.

## Scenario A operator cues

1. Register or sign in both rehearsal users and state which session is Player A or B.
2. Select the fixed language and enter ranked matchmaking from both sessions.
3. Point out the shared server start/deadline plus P0 masked opponent connection and Run/Submit status.
4. Run the public example to gain energy and submit the prepared wrong answer; show `WRONG_ANSWER` without match termination.
5. Submit the prepared accepted solution through the configured Judge and show the submission lock. State when the local fake adapter is selected.
6. At 03:30, use the pre-finished rehearsal match and show the committed outcome, rating/RP, automatic result post and player record.
7. Keep attack exchange, Surrender and handle search out of the live path until each has evidence.

A manual seed may unblock rehearsal only under the documented Issue #73 procedure and disclosure above; it does not satisfy the no-database-edit acceptance. No other manual database edit, hidden operator API or prewritten result may substitute for a live step.

## Recovery matrix

| Failure | Decision | Operator action | Disclosure |
|---|---|---|---|
| Browser-only failure | Retry once if services remain healthy | Reload or reconnect within the bounded scenario | State that the client reconnected |
| One application service unhealthy | Stop live path | Show preflight/log correlation, then use the labeled screenshot sequence | State failure time and affected service |
| Selected Judge adapter unavailable or slow | Stop live judging | Show the classified platform failure, then use the labeled screenshot sequence | Name the selected adapter; never call platform failure user-code failure |
| Kafka projection delayed | Preserve authoritative result | Show battle result first; inspect event/projection health | State eventual consistency and whether it converged |
| AWS target unavailable | Activate the documented isolated local fallback only if preflight passes | Use the pre-rehearsed local environment | State the deployment substitution and selected Judge adapter |
| Unknown or inconsistent state | End the live scenario | Do not mutate data to repair it on stage | Announce the frozen rehearsal screenshot sequence and move to it |

After any fallback, the presenter distinguishes live output from recorded evidence verbally and on screen.

## Post-run evidence

Record start/end time, revision, preflight result, scenario result, deviations, screenshots or recording location, and the owner of every follow-up. A successful rehearsal is evidence for that exact build and environment only; repeat after changes to the golden path, seed, Judge0 runtime, deployment or presentation sequence.
