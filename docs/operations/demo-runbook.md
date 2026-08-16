# Demo runbook

Owner: 최정민

Status: Local start baseline active; live sequence blocked by Scenario A

Last verified: 2026-08-14 against D3-UX-002 and the executable local start/preflight path

Requirement: D3-UX-002

Related: [presentation outline](../presentation/outline.md), [test plan](../quality/test-plan.md), [deployment plan](deployment-plan.md)

The target allocation is 15 minutes of presentation and demonstration plus 5 minutes of buffer or questions. 최정민 leads; service owners answer technical questions. Timing remains adjustable when the exact presentation rubric is known.

## Evidence boundary

The current repository can validate scaffold structure, contracts, builds, the Compose model, and the Judge-owned HTTP/persistence/outbox path with a deterministic local fake. A selectable real Judge0 adapter exists and has bounded local HTTP-fixture tests. A dedicated AWS Judge0 host and its six-language host smoke are active, but the judge-service-to-host private call is **NOT RUN** and its route is **PENDING**. The live ranked path, application deployment, final screenshots, measured latency and backup recording are also not yet evidenced. Mark each item below with a revision and capture time during rehearsal; do not replace a failed live step with an unlabeled mock.

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
| Scenario A | Service owners | Two browsers complete ranked flow without database edits | Blocked: behavior not implemented |
| Scenarios B and C | Battle/Judge owners | Reconnect, surrender and incident void are deterministic | PARTIAL PASS: deterministic domain and autonomous match/reconnect deadline tests pass; live two-browser evidence NOT RUN |
| Six language runtimes | Judge owner | Versioned smoke cases pass on designated host | PASS for host: pinned Judge0 CE 1.13.1 matrix and 12/12 language cases |
| Judge application route | 윤서진 | judge-service reaches the designated host through the source-SG-only path and repeats the sanitized six-runtime smoke | NOT RUN: private route and deployment egress PENDING |
| Observability | 윤서진 | Health, correlation and failure views are identified | Local health and ingress correlation PASS; dashboards pending |
| Deployment target | 윤서진 | Environment and rollback owner are bound | AWS `UNKNOWN`; local fallback planned |
| Backup recording | 최정민 | Same build and real services are visibly labeled | Not recorded |

All gates required by the chosen live path must be green before announcing demo readiness.

## Start and preflight

On the frozen local environment:

```bash
pnpm install --frozen-lockfile
pnpm verify:scaffold
./gradlew build
docker compose -f infra/compose.yaml config --quiet
pnpm local:start
```

`pnpm local:start` runs `demo:preflight` after all local processes become healthy. Preflight requires Web, discovery, config, gateway, four domain services, PostgreSQL, Redis, Kafka, and the adapter selected for that build. It also waits for the anonymous Gateway route `GET /api/v1/community/matches/00000000-0000-4000-8000-000000000000` to converge to its contract `404`: transient network and `5xx` failures are retried for a bounded interval, while another status or retry exhaustion produces `NOT READY`. The route origin is derived from `D3_GATEWAY_HEALTH_URL`, and output contains status/attempt metadata but never the response body. The deterministic fake is the local-development default; the primary presentation must explicitly select and verify real Judge0. Archive the terminal output with the revision; a `NOT READY` result blocks the live path and browser launch.

Before opening the browser, confirm:

- the `gateway-community-route` preflight record is `ok: true` with `status: 404`; do not open either browser before this routed gate passes;
- both sessions are independent and already at the sign-in screen;
- seeded users, fixed ranked problem and deterministic attack configuration match the recorded seed;
- clocks are synchronized enough to interpret server timestamps;
- observability and operator terminals are visible but contain no source, token or hidden test;
- the backup recording opens locally and is labeled with its build revision.

## Timed presentation path

| Time | Lead action | Required visible evidence |
|---|---|---|
| 00:00–02:00 | State the problem and D³ value | One continuous login → battle → public record story |
| 02:00–04:00 | Explain boundaries | System context, Judge0 isolation and data ownership |
| 04:00–11:00 | Run Scenario A in two browser sessions | Same server clock, reversible attack, judged submit, result, rating/RP, result post and record |
| 11:00–13:00 | Show reliability evidence | Reconnect/surrender or incident-void recording plus correlation trace |
| 13:00–15:00 | Summarize verification and trade-offs | Test evidence, privacy boundary, AWS or local deployment truth |
| 15:00–20:00 | Buffer and questions | Service owners answer from recorded evidence |

## Scenario A operator cues

1. Sign in both seeded users and state which session is Player A or B.
2. Select the fixed language and enter ranked matchmaking from both sessions.
3. Point out the shared server start/deadline plus P0 masked opponent connection and Run/Submit status. Show detailed progress only if its P1 feature boundary has been activated and verified.
4. Run public examples; trigger one warned, reversible attack and show block or reflect.
5. Submit the prepared accepted solution through the real judge and show submission lock.
6. Show the committed outcome, score composition, rating and RP changes in both clients.
7. Navigate through the automatic ranked-result post to the searchable match record.
8. Show one correlation identifier across judge, battle and projection evidence without exposing source.

No manual database edit, hidden operator API or prewritten result may substitute for a step.

## Recovery matrix

| Failure | Decision | Operator action | Disclosure |
|---|---|---|---|
| Browser-only failure | Retry once if services remain healthy | Reload or reconnect within the bounded scenario | State that the client reconnected |
| One application service unhealthy | Stop live path | Show preflight/log correlation, then use the labeled recording | State failure time and affected service |
| Judge0 unavailable or slow | Stop live judging | Show classified platform failure evidence and use recording | State that live Judge0 failed; never call it user-code failure |
| Kafka projection delayed | Preserve authoritative result | Show battle result first; inspect event/projection health | State eventual consistency and whether it converged |
| AWS target unavailable | Activate documented local fallback only if preflight passes | Use the pre-rehearsed local environment | State the deployment substitution |
| Unknown or inconsistent state | End the scenario | Do not mutate data to repair it on stage | Move to labeled recording and Q&A |

After any fallback, the presenter distinguishes live output from recorded evidence verbally and on screen.

## Post-run evidence

Record start/end time, revision, preflight result, scenario result, deviations, screenshots or recording location, and the owner of every follow-up. A successful rehearsal is evidence for that exact build and environment only; repeat after changes to the golden path, seed, Judge0 runtime, deployment or presentation sequence.
