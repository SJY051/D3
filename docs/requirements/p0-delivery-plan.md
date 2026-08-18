# D³ P0 delivery plan

- Owner: 윤서진 (development lead)
- Status: Active operating baseline
- Last verified: 2026-08-16 against origin/main at 506185a, live GitHub state and issue #60 Community projection evidence
- Scope: M-01 through M-10 and D3-QLT-001
- Target: freeze a demonstrable release candidate on 2026-08-19 and present it on 2026-08-20

이 문서는 P0 작업의 배정, 시작, 리뷰, 병합 순서를 정하는 기준이다. GitHub 이슈와 PR은 실제 실행 상태의 원본이며, 담당자·의존성·병합 게이트가 바뀌면 이 문서도 같은 작업에서 갱신한다. 기능의 세부 수용 기준은 `docs/specs/d3-mvp.md`가 우선한다.

## 1. Delivery objective

이번 프로토타입은 아래 한 흐름이 끊기지 않고 시연되는 것을 최우선으로 한다.

```text
local sign-in
  -> ranked queue
  -> two-player realtime battle
  -> Run / Submit through Judge0
  -> committed result and rating
  -> public result post and searchable record
```

운영 원칙은 다음과 같다.

- P1 기능은 P0 golden path가 통과할 때까지 기본 비활성 상태를 유지한다.
- 개별 PR의 CI가 성공해도 통합 선행 PR이 병합되지 않았으면 병합 준비 완료로 보지 않는다.
- 하위 작업은 인터페이스나 순수 도메인 테스트까지 병렬 진행할 수 있지만, 선행 계약이 확정되기 전 임의의 통합 계약을 만들지 않는다.
- 완료 표시는 테스트 이름, 실행 명령, 런타임 증거 또는 시연 기록 중 하나 이상을 연결한 경우에만 사용한다.

## 2. Current pull-request decisions

| PR | 작성자 | 결정 | 근거와 다음 동작 |
| --- | --- | --- | --- |
| [#25](https://github.com/SJY051/D3/pull/25) | `david3123123` | **MERGED** | Windows에서 계약 검증 경로를 정규화하는 단일 목적 수정이다. CI 5/5 통과 후 코멘트를 남기고 2026-08-14에 squash merge했다. Merge commit: `bf8db818753b6645eafc2573bfb181047bd3b5f4`. |
| [#23](https://github.com/SJY051/D3/pull/23) | `SJY051` | **MERGED** | 플랫폼·DB migration 기반을 CI 5/5와 미해결 review thread 0건으로 마감하고 2026-08-14에 squash merge했다. Merge commit: `2f71323cc1eae59687ff3feae2b6d0b3ecd85b6f`. 병합을 막지 않은 legacy canonicalization P2는 [#32](https://github.com/SJY051/D3/issues/32)로 분리했다. |
| [#34](https://github.com/SJY051/D3/pull/34) | `SJY051` | **MERGED** | P0 delivery control을 최신 main과 동기화하고 기존 review thread 3건을 해결했다. CI 5/5와 미해결 review thread 0건 후 2026-08-14에 squash merge했다. Merge commit: `3bde9100d1a87ff6a15fec0b5884acc9752c46cc`. |
| [#36](https://github.com/SJY051/D3/pull/36) | `SJY051` | **MERGED — FOUNDATION #27** | Gateway canonical Identity ingress와 Judge service-principal 식별을 회귀 테스트로 고정했다. 모듈 전체 테스트, scaffold, targeted security diff scan 0건과 CI 5/5 후 2026-08-14에 squash merge했다. Merge commit: `eeb33c987b79bfbfef207c04fb8af6747bbb7759`. 남은 cookie/key lifecycle, Identity issuance와 Battle acquisition은 PR #51이 완성한다. |
| [#26](https://github.com/SJY051/D3/pull/26) | `GledoubleN` | **MERGED** | 최신 `main`의 forward-only migration, ERD와 upgrade evidence를 보존하고 CI 5/5 및 미해결 review thread 0건을 확인한 뒤 2026-08-15에 squash merge했다. Merge commit: `2e2168ef44cdf9dd9b40fe5fe7bffb5419d2ad59`. |
| [#28](https://github.com/SJY051/D3/pull/28) | `david3123123` | **MERGED — PARTIAL #18** | 승인 WF-01/02/03/05/06 API-backed golden-path 화면, 공용 refresh-cookie session 경계, 단일 Idempotency-Key queue replay, viewer-relative result, dark-first 접근성을 병합했다. 재작업 4 사이클(P1/P2/Codex finding 해소)과 브라우저 재현 후 2026-08-18에 squash merge했다. Merge commit: `25359adee498c036af8d62ff26f236b3dbc82d88`. 두-세션 통합 인수는 #19에서 진행한다. |
| [#33](https://github.com/SJY051/D3/pull/33) | `SJY051` | **MERGED — PARTIAL #15** | legacy lifecycle/result 정규화, 단일-snapshot read, single-active-match fencing, viewer-relative v2 snapshot을 회귀 테스트로 보강했다. 최신 CI 5/5와 미해결 review thread 0건 후 2026-08-14에 squash merge했다. Merge commit: `4e390722d44f2d04c9a2b4020e62392c04edf9c7`. 마지막 non-blocking P2 세 건은 [#35](https://github.com/SJY051/D3/issues/35)로 이관했다. WebSocket/auth/Judge 연결과 두 세션 증거는 후속이며 #15 전체 완료로 표시하지 않는다. |
| [#38](https://github.com/SJY051/D3/pull/38) | `SJY051` | **MERGED — PARTIAL #15** | authenticated participant WebSocket handshake, viewer-relative latest snapshot, Redis cross-instance fan-out와 bounded delivery를 활성화했다. 최신 CI 5/5와 미해결 review thread 0건 후 2026-08-14에 squash merge했다. Merge commit: `2c85e854c59a831d86f92267284e14f05977b955`. 비차단 live-session bound와 saturation convergence는 [#37](https://github.com/SJY051/D3/issues/37), [#39](https://github.com/SJY051/D3/issues/39)로 이관했다. |
| [#40](https://github.com/SJY051/D3/pull/40) | `SJY051` | **MERGED — PARTIAL #15** | closed READY/SURRENDER command, participant-bound receipt, single accepted server instant와 strict frame parsing을 활성화했다. 최종 HEAD `dafc65d`에서 CI 5/5와 Codex 재리뷰 major issue 0건, 미해결 thread 0건 후 squash merge했다. Merge commit: `069e2ce2dece99a57a359f43924401acda9fa621`. 비차단 optimistic command conflict는 [#41](https://github.com/SJY051/D3/issues/41)로 이관했다. |
| [#42](https://github.com/SJY051/D3/pull/42) | `SJY051` | **MERGED — PARTIAL #15** | transport-owned PostgreSQL connection generation과 stale-close/command fencing을 고정했다. CI 5/5 후 2026-08-14에 squash merge했다. Merge commit: `1f23faf3c7cf509e4098b3c17fd3ccd25376ffe8`. JUDGING socket-close 정책은 [#43](https://github.com/SJY051/D3/issues/43)으로 이관했다. |
| [#44](https://github.com/SJY051/D3/pull/44) | `SJY051` | **MERGED — PARTIAL #15** | PostgreSQL 권위의 match/reconnect deadline driver, bounded async fan-out, generation-fenced retry와 local-session resync를 활성화했다. 최종 HEAD `4a4d285`에서 Battle 118 PASS/7 기존 scaffold SKIP, CI 5/5, Codex major issue 0건을 확인하고 2026-08-14에 squash merge했다. Merge commit: `7bbf17cdd1a3086ae42984d0f59e4b3d741e4b4c`. 비차단 polling index는 [#45](https://github.com/SJY051/D3/issues/45)로 이관했다. |
| [#50](https://github.com/SJY051/D3/pull/50) | `SJY051` | **MERGED — PARTIAL #16** | reversible garbage attack, authoritative energy/event persistence, versioned WebSocket v3 attack commands/snapshots와 Gateway credential bridge를 활성화했다. CI 5/5와 미해결 review thread 0건 후 2026-08-15에 squash merge했다. Merge commit: `d99829d905ab7ff729bc878c6edc83f23bd3096c`. result/rating/outbox 연결과 browser overlay는 후속이다. |
| [#51](https://github.com/SJY051/D3/pull/51) | `SJY051` | **MERGED — COMPLETE #27** | user `battle.play`, Identity short-lived Judge service-token issuance, Battle acquisition, explicit Judge clock skew, stable deploy signing key와 cookie profile policy를 구현했다. targeted Identity/Battle/Judge/Gateway tests와 local config tests PASS. CI 5/5 후 2026-08-15에 squash merge했다. Merge commit: `43495dbf2112017bf3a0a2360b6371ce4d815214`. #27은 completed로 종료됐다. |
| [#31](https://github.com/SJY051/D3/pull/31) | `GledoubleN` | **MERGED — PARTIAL #17** | public feed, sanitized Markdown, keyset pagination을 병합했다. Merge commit: `6b618a00f5744752e359361a34e507102a04db67`. user/match/rating projection consumer는 후속이다. |
| [#62](https://github.com/SJY051/D3/pull/62) | `SJY051` | **MERGED — PARTIAL #17** | `match.finished.v1` exactly-once Community inbox/projection consumer를 병합했다. CI 5/5, Codex major issue 0건, 미해결 thread 0건 후 2026-08-16에 squash merge했다. Merge commit: `506185a505669871e3c9e74326e52b8c464ad0eb`. 남은 #17 범위는 자동 result post, record 조회, rating/user projection이다. |
| [#70](https://github.com/SJY051/D3/pull/70) | `GledoubleN` | **MERGED — PARTIAL #17** | `rating.changed.v1` consumer와 rating-first `profile_projection` upsert, V5 migration, Kafka/PostgreSQL 통합 테스트, 문서 정합을 병합했다. CI 5/5, Codex major issue 0건, 미해결 thread 0건 후 2026-08-18에 squash merge했다. Merge commit: `ca73b9c4cc4320dfc35220116cd576ce7b293cd9`. 남은 #17 범위는 Identity `user-profile.changed.v1` projection과 handle 검색이다. |
| [#52](https://github.com/SJY051/D3/pull/52) | `SJY051` | **MERGED — PARTIAL #15** | Judge submission/result 통합과 accepted-submission correlation을 병합했다. Merge commit: `2eb5d804aba8709590149f7efa08686f6b37c91c`. |
| [#53](https://github.com/SJY051/D3/pull/53) | `SJY051` | **MERGED — PARTIAL #16** | versioned scoring, exactly-once rating/RP, `match.finished.v1`/`rating.changed.v1` outbox를 병합했다. Merge commit: `767a73a14266adb80d447c2014bd55ba04f944ec`. |
| [#54](https://github.com/SJY051/D3/pull/54) | `SJY051` | **MERGED — COMPLETE #16** | Battle v3 attack command/overlay UI와 browser evidence를 병합했다. Merge commit: `b899e071b0434bae06dcea5a1d9339da0f420d44`. #16과 #48은 종료됐다. |
| [#55](https://github.com/SJY051/D3/pull/55) | `SJY051` | **MERGED — COMPLETE #15** | repository proxy, RUN/SUBMIT receipt 제약 V11, `JUDGE_RESULT` snapshot 투영의 런타임 통합 결함 3건을 수정하고 두 세션 실경로 증거를 남겼다. Merge commit: `2bc10750ec3e7f87ba15d9c2324cbef2181c093a`. #15는 종료됐다. |

## 3. Critical path and parallel lanes

### 3.1 Critical merge path

```text
#23 platform (merged)
  -> #26 identity rebase + #27 auth contract
  -> #15 authenticated realtime and Judge integration over merged #33 foundation
  -> #16 scoring / rating / attack
  -> #17 result projections + #18 integrated web golden path
  -> #19 release-candidate rehearsal
```

#15와 #16은 2026-08-15에 병합 완료됐다. 남은 critical path는 #17 projection consumer -> #18 web golden path -> #19 rehearsal이다.

이 순서는 병합 순서다. 개발 착수 순서는 완전히 직렬일 필요가 없다.

### 3.2 Work allowed in parallel

- #15의 매칭·match state 기반은 #33으로, Gateway WebSocket credential 변환에 필요한 #27 경계는 #36/#51로, outbound participant snapshot transport는 #38로, command transport는 #40으로, disconnect fencing은 #42로, autonomous deadline driver는 #44로 병합됐다. Identity는 user `battle.play`와 Battle용 Judge service token을 발급하며, 남은 accepted-submission correlation과 judged-event 종료 처리는 #15에서 연결한다.
- #16은 점수·rating·RP의 고정 계산 예제와 불변식을 먼저 작성할 수 있다. 결과 commit과 이벤트 발행은 #15 lifecycle 및 Judge evidence 계약 뒤에 연결한다.
- #17은 #23의 Community DB 기반이 안정되면 post/feed CRUD부터 진행할 수 있다. 사용자·경기·rating projection은 각 producer event가 고정된 뒤 연결한다.
- #18은 승인된 wireframe에 대응하는 route shell, 상태 컴포넌트, API adapter를 먼저 만들 수 있다. 실제 인증·대전·결과 연동은 각 API가 병합된 뒤 활성화한다.
- #19는 지금부터 acceptance matrix와 runbook을 갱신한다. 전체 E2E와 녹화는 P0 통합 SHA가 고정된 뒤 수행한다.

## 4. Work lanes and ownership

| Lane | 이슈 / 요구사항 | 예정 담당자 | 리뷰·지원 | 시작 게이트 | 병합·완료 게이트 | 현재 상태 |
| --- | --- | --- | --- | --- | --- | --- |
| F0 Platform and migrations | [#11](https://github.com/SJY051/D3/issues/11), PR #23 / 기반 전체 | **윤서진** | 팀원 리뷰 | 완료 | 과거 checksum 보존, fresh install와 upgrade path, Compose/runtime preflight, CI와 bot/human review | **병합 완료** (`2f71323`) |
| F1 Identity sessions | [#12](https://github.com/SJY051/D3/issues/12), PR #26 / M-01 | **임수혁** (`GledoubleN`) | 윤서진 통합 리뷰 | 병합 완료 | forward-only migration, 단일 security chain, Gateway canonical path, rotating HttpOnly cookie, stable deploy signing key | **완료** (`2e2168e`): CI 5/5와 migration immutability evidence PASS |
| F2 Cross-service auth and contracts | [#27](https://github.com/SJY051/D3/issues/27), PR #36/#51 / D3-SEC-001 | **윤서진** | 임수혁 지원 | PR #51 검증 | Battle service token 발급·획득·검증, canonical route, browser session transport, negative authorization evidence | **완료** (#51 merge, #27 closed) |
| B1 Ranked realtime lifecycle | [#15](https://github.com/SJY051/D3/issues/15), PR #33/#38/#40/#42/#44 / M-02, M-03 | **윤서진** | 임수혁 backend 지원 | #33/#36/#38/#40/#42/#44/#51 기반 병합 | 두 client 매칭, server clock, reconnect, surrender, incident void, authenticated WS, Judge correlation | **완료** (#15는 #55까지의 merge와 두 세션 런타임 증거로 closed) |
| B2 Outcome, rating and attack | [#16](https://github.com/SJY051/D3/issues/16) / M-05, M-06, M-07 | **윤서진** | 최정민 acceptance example 검토 | 계산식 테스트는 즉시 | versioned scoring, exactly-once rating/RP, reversible attack, result outbox, repeatable examples | **완료** (#52/#53/#54 merge, #16 closed) |
| J0 Judge boundary | [#13](https://github.com/SJY051/D3/issues/13), [#14](https://github.com/SJY051/D3/issues/14), [#59](https://github.com/SJY051/D3/issues/59) / M-04 | **윤서진** | 팀원 smoke 지원 | 완료 | host-local 격리와 source-SG application-adapter 경로, 6개 runtime, privacy, cleanup 증거 | **완료**: host-local 54/54 PASS (`61189c99-7372-4977-96b1-72ae88767b02`), production adapter 6-runtime private-route PASS (`a38944c3-8073-47de-b414-f3bd610acdf8`), 임시 runner/IAM/SG 제거 및 Judge0 zero ingress 복구 |
| C1 Community and projections | [#17](https://github.com/SJY051/D3/issues/17), [#60](https://github.com/SJY051/D3/issues/60), [#64](https://github.com/SJY051/D3/issues/64), PR #31/#62 / M-08, M-09 | **임수혁** (`GledoubleN`) | 윤서진 event/privacy 리뷰 및 #60/#64 골든패스 projection | #31 병합 및 producer 이벤트 #53 고정 | Markdown/privacy, public feed, idempotent user/match/rating projections, replay evidence | feed 병합 완료 (`6b618a0`); #60/#62로 `match.finished.v1` inbox/ACTIVE match projection 완료; #64 자동 result post·공개 record 조회는 SJY051 담당; rating projection은 [#70](https://github.com/SJY051/D3/pull/70)으로 완료; Identity `user-profile.changed.v1` producer(V3 `profile_version` + 같은 트랜잭션 outbox + backfill sweep)는 [#75](https://github.com/SJY051/D3/pull/75)로 병합 완료; Community `user-profile.changed.v1` consumer(handle/identity_source_version upsert, rating-first 정합, 역순 무시)와 `profile_projection` handle 검색 read API는 이 PR에서 구현 — 남은 것은 통합 browser UI trace(#19) |
| W1 Web golden path | [#18](https://github.com/SJY051/D3/issues/18), PR #28 / D3-UX-001, D3-UX-002 | **박주형** (`david3123123`) | 최정민 wireframe, 윤서진 API 리뷰 | 해당 wireframe 승인 후 | 최신 main rebase, 실제 API adapter, 접근 가능한 상태 UI, 두 세션 흐름, P1 mock 미노출 | **병합 완료** (`25359ad`, 2026-08-18): 승인 WF-01/02/03/05/06 API-backed 화면, 공용 session 경계, 단일 key queue replay, viewer-relative result, dark-first 접근성. 두 세션 통합 인수는 #19에서 진행 |
| Q1 QA and rehearsal | [#19](https://github.com/SJY051/D3/issues/19) / M-10, D3-QLT-001 | **박주형** (`david3123123`, 현재 assignee) | 최정민(`Justgettinby`) 발표·acceptance 주도, 전원 evidence 제공 | acceptance matrix는 즉시 | frozen SHA, preflight, full scenario, pass/fail/skip/not-run 기록, 녹화와 fallback | 진행 중: 중간점검용 frozen SHA `25359ad` 고정(2026-08-18), preflight·리허설·녹화 예정; 최정민 온보딩 완료(2026-08-18)로 assignee 정합화 협의 필요 |

담당자가 휴가·병목 등으로 바뀌면 실제 GitHub assignee와 이 표를 함께 갱신한다. 이름 옆 GitHub 계정은 현재 PR 작성자와 예정 담당자의 연결을 명확히 하기 위한 표기다.

## 5. Lead-owned foundation decisions

아래 항목은 여러 팀원의 작업을 동시에 막을 수 있으므로 윤서진이 기준을 먼저 고정한다.

### 5.1 Canonical Identity ingress

권장 외부 경로는 다음과 같다.

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET/PATCH /api/v1/users/me`

Gateway는 외부 `/api` prefix를 정확히 한 번 제거하고 Identity의 `/v1/...` 경로로 전달한다. register, login, refresh는 anonymous allowlist에 포함한다. logout도 만료된 access token 없이 세션을 폐기할 수 있도록 Gateway에서는 익명 통과시키되, Identity가 rotating refresh cookie를 요구하고 폐기 결과를 idempotent하게 처리한다. 그 외 API는 Gateway와 각 service 모두에서 인증·인가한다.

### 5.2 Browser session transport

기본 선택은 짧은 수명의 access token을 browser memory에만 유지하고, rotating refresh token은 `HttpOnly`, `SameSite=Lax` cookie로 전달하는 방식이다. 배포 HTTPS에서는 `Secure`를 강제하며 로컬 HTTP 예외를 profile로 분리한다. refresh reuse detection, logout revocation, credential 미출력 로그를 #26 테스트로 고정한다.

배포/demo 재시작 후에도 기존 access token을 검증해야 하므로 signing key lifecycle을 명시한다. 로컬 ephemeral key가 허용된다면 local profile에만 한정하고, demo/deploy profile은 안정된 secret source를 사용한다.

### 5.3 Battle-to-Judge machine identity

Battle은 사용자 access token을 Judge로 전달하지 않는다. Identity가 발급한 짧은 수명의 service token을 사용한다.

필수 claim과 검증은 다음과 같다.

- audience: `judge-service`
- scope: 필요한 호출에 따라 `judge.submit` 또는 `judge.read`
- client identity: `client_id`가 `battle-service`
- service discriminator: `token_use`가 `service`
- Judge negative test: 동일 audience/scope처럼 보여도 user token은 거부
- key rotation과 clock skew는 Identity/Judge 양쪽 설정으로 명시

이 계약은 F2 lead foundation issue에서 고정하고 #15가 소비한다.

### 5.4 Event and projection freeze

현재 기준 이벤트는 `user-profile.changed.v1`, `submission.judged.v1`, `match.finished.v1`, `rating.changed.v1`이다.

- Identity는 Community가 사용할 public profile event를 outbox로 발행한다.
- Battle은 committed match와 rating 변화를 outbox로 발행한다.
- Community는 inbox/idempotency와 aggregate version으로 중복·역순 event를 처리한다.
- 이벤트에는 private source, refresh token, hidden test data를 포함하지 않는다.
- 기존 v1 payload만으로 projection을 만들 수 없다면 cross-DB query로 우회하지 않고 versioned event 또는 bounded read contract를 추가한다.

### 5.5 Frontend integration contract

- 하나의 API adapter가 Gateway base URL, access token, refresh retry, correlation ID, v1 error envelope를 처리한다.
- route component가 직접 서비스별 URL이나 token 저장 방식을 만들지 않는다.
- loading, empty, disconnected, recoverable error, terminal error 상태를 wireframe별로 고정한다.
- 구현되지 않은 P1 기능은 비활성 표시하거나 숨기며 성공 mock으로 보이지 않게 한다.

## 6. Issue completion gates

### 6.1 #23 — platform and migration base

- **Completed 2026-08-14:** merge commit `2f71323cc1eae59687ff3feae2b6d0b3ecd85b6f`.
- 공개된 V1 checksum 보존, V2 이상 forward-only migration, fresh/upgrade 검증, 서비스별 DB 소유권을 같은 revision에서 검증했다.
- CI 5/5와 미해결 review thread 0건을 확인했다.
- P0/P1이 아닌 legacy canonicalization 보강은 #32로 이관했으며 완료로 오인하지 않는다.

### 6.2 #26 — Identity sessions

- #23 병합 후 최신 `main`에 rebase한다.
- #23의 Identity V1과 `login_identity`, `refresh_session`, `outbox_event`를 보존하고 필요한 변경은 새 migration으로 추가한다.
- root scaffold와 구현 package에 중복된 `IdentitySecurityConfiguration` 또는 `SecurityFilterChain`이 남지 않게 한다.
- Gateway route와 controller path를 5.1의 canonical ingress로 맞춘다.
- register, login, refresh의 anonymous 접근과 profile의 authenticated 접근을 negative test까지 증명한다.
- `Gateway -> Identity` 경로로 register -> login -> refresh -> authenticated profile 통합 테스트 또는 동일 수준의 runtime trace를 남긴다.
- username/email uniqueness, password hashing, refresh rotation/reuse/revoke, session expiry를 PostgreSQL 제약과 서비스 테스트로 검증한다.
- seed/demo credential은 실제 secret으로 저장하거나 로그·문서에 출력하지 않는다.

### 6.3 #15 — ranked realtime lifecycle

- 동일 언어를 선택한 두 사용자만 하나의 ranked match로 원자적으로 매칭한다.
- public rating이 가까운 사용자부터 시작하고 서버가 측정한 대기 구간에 따라 허용 범위를 결정론적으로 확대하며, 최대 범위와 경계 시각을 테스트로 고정한다.
- 서버 시간이 lifecycle과 deadline의 유일한 권위이며 client는 RTT 보정 표시만 한다.
- WebSocket handshake와 match command 모두 authenticated participant만 허용한다.
- 상대방 observation은 허용된 connectivity와 masked run/submit 상태만 포함한다. 상대 identifier, literal, source와 private snapshot이 계약·event·오류에 포함되지 않음을 negative privacy test로 증명한다.
- 30초 reconnect window, disconnect 표시, surrender, incident `VOIDED`를 결정론적 state test로 증명한다.
- Run/Submit은 Judge request와 match/player/submission correlation을 유지한다.
- service token은 5.3 계약을 사용하며 사용자 token이나 source를 로그에 남기지 않는다.

### 6.4 #16 — scoring, rating and attack

- speed, repeated runtime evidence, submission discipline을 versioned formula로 계산한다.
- 같은 input/evidence는 같은 결과를 내는 고정 예제와 boundary test를 둔다.
- MMR/Elo, seasonal RP, tier는 별도 필드와 규칙을 사용한다.
- ranked committed result만 rating/RP를 정확히 한 번 변경한다. unranked와 `VOIDED`는 변경하지 않는다.
- 최소 한 개의 warned, reversible, server-authoritative attack을 제공하며 저장 source는 절대 변경하지 않는다.
- match result와 rating event가 동일한 commit/outbox 경계에서 재처리 안전하게 발행된다.

### 6.5 #17 — community and result projections

- fenced code block을 지원하는 sanitized Markdown과 본문 글자 수 규칙을 검증한다.
- P0 public visibility만 실제 기능으로 노출하고 circle/secret 기능은 feature boundary 뒤에 둔다.
- public feed는 안정된 keyset pagination을 사용한다.
- profile, match, rating projection은 idempotent하고 중복·역순 event 테스트를 통과한다.
- 공개 result post와 searchable match record는 원본 match ID로 추적 가능하다.
- 게시물이나 projection API에서 private source, hidden test, internal Judge credential을 반환하지 않는다.

### 6.6 #18 — web golden path

- `docs/wireframes/README.md`에서 승인된 WF ID만 styled implementation으로 진행한다.
- sign-in, queue, battle, result, feed/record 흐름이 실제 API adapter를 사용한다.
- keyboard-only 조작, focus, error announcement, disconnect/reconnect 상태를 검증한다.
- 두 browser session과 deterministic fake Judge로 golden path를 재현한다.
- 실제 Judge0 경로는 별도 smoke로 증명하고 fake 결과를 production-ready로 표시하지 않는다.

### 6.7 #19 — QA and rehearsal

- 모든 M-01~M-10에 owner, revision, evidence, PASS/FAIL/SKIP/NOT RUN 상태를 둔다.
- 발표 대상 commit SHA와 image/config revision을 고정한다.
- local과 deployed target 모두 preflight하고 어느 쪽을 live/fallback으로 쓸지 표기한다.
- 20분 발표 동선, 질문 담당, 실패 시 전환 조건을 리허설한다.
- 최종 흐름을 녹화하고 민감정보·개인 source가 보이지 않는지 확인한다.

## 7. Calendar and daily target

| 날짜 | 목표 | 종료 조건 |
| --- | --- | --- |
| 8월 14일 | Foundation freeze | #23 병합 완료, #26 rebase 착수, #27 Identity route/session 및 service-token 결정, #15 기반 rebase·검증, wireframe 리뷰 상태 기록 |
| 8월 15~16일 | Integration spine | #26 병합, #15 lifecycle과 Battle-to-Judge 연결, web auth/queue/battle shell, community post/feed slice |
| 8월 17일 | Outcome and projection | #16 scoring/rating/result commit, #17 profile/match/rating projections, result/record UI |
| 8월 18일 | Hardening | 두 세션 E2E, reconnect/surrender/void, privacy/security regression, 가능한 경우 AWS app-to-Judge private smoke |
| 8월 19일 | Feature freeze and rehearsal | P1 중단, RC SHA 고정, live/fallback preflight, 전체 녹화와 발표 리허설 |
| 8월 20일 | Presentation | blocker-only 수정, 발표와 질의응답 |

일정이 밀리면 기능 수를 늘리지 않는다. M-01~M-10 중 끊긴 golden-path 구간을 먼저 복구하고 P1, 시각 효과, 추가 게임 모드는 자른다.

### 7.1 Approved AWS operating boundary

- 승인 창은 2026-08-14 18:00~24:00, 8월 15일과 16일 각각 09:00~24:00이며 최대 compute는 36시간이다.
- 허용 대상은 서울 리전의 Judge0 전용 `t3.large` 1대와 gp3 40 GiB, SSM, Judge0 token Secret 1개, 기본 CloudWatch다. 애플리케이션, PostgreSQL, Redis, Kafka는 로컬에 유지한다. Issue #59의 `t3.small` application-side runner는 source-SG 경로 증명 동안만 사용하고 즉시 제거했다.
- 2026-08-15 확인 기준 `d3-judge0` systemd는 active이고 authenticated Judge0 CE 1.13.1 API와 host-local sanitized smoke는 19/19 boundary·health checks 및 35/35 execution cases PASS다. Host evidence command는 `61189c99-7372-4977-96b1-72ae88767b02`다. Issue #59 production-adapter private-route command `a38944c3-8073-47de-b414-f3bd610acdf8`도 6개 runtime과 output privacy를 통과했고 임시 route와 runner를 제거한 뒤 Judge0 SG zero ingress를 재확인했다. Docker Compose 상태는 이 단계에서 별도 확인하지 않았다.
- 인스턴스는 2026-08-14 01:45부터 실행됐지만 공지 전 사용은 승인된 야간·주말 36시간 집계와 분리해 기록한다.
- EventBridge Scheduler group `d3-judge0-20260814-16`에 일회성 일정 5개를 구성했다. 8월 14일 정지와 15일 시작 일정은 실행 후 자동 삭제됐고, 15일 23:55 정지와 16일 09:00 시작·23:55 정지 일정 3개는 ENABLED다. 모두 `Asia/Seoul`, flexible window OFF, 완료 후 자동 삭제다.
- 실행 역할 `d3-Scheduler-Judge0-20260814-16`은 `i-0981ab438329d3e62`의 Start/Stop만 허용하고 다른 인스턴스는 암묵적으로 거부한다. trust policy는 전용 schedule group ARN과 계정으로 제한한다.
- 각 일정은 최대 event age 240초와 재시도 2회를 사용한다. 자동 정지 전에 새 Judge 제출을 drain하고, 실행 후 실제 `stopped` 또는 `running` 상태를 수동 검증한다.
- 일회성 schedule은 실행 후 자동 삭제되지만 schedule group과 IAM role은 남는다. 8월 16일 최종 정지와 schedule 0개를 확인한 뒤 두 리소스를 수동 정리한다.
- 이 시간 고정 운영 게이트는 9절 점수와 무관하게 해당 마감 전에 우선 처리한다.

## 8. Team operating rules

- 한 사람의 implementation PR WIP는 기본 1개로 제한한다.
- contract, DB migration, event, Gateway route, auth 경계 변경은 윤서진 리뷰를 필수로 한다.
- styled UI는 최정민의 wireframe 승인 또는 명시적 deviation 기록 후 시작한다.
- 모든 PR은 연결 이슈, requirement ID, PASS/FAIL/SKIP/NOT RUN, 최신 base, CI, bot review, human review를 갖춘다.
- PR은 기본적으로 Ready for review로 연다. 의도적으로 불완전해 자동 리뷰 대상에서 제외할 때만 Draft를 사용한다.
- P0/P1 review finding은 해결하거나 범위를 줄여야 병합한다. P2는 golden path 중단, 보안·개인정보 침해, 데이터 손상, 기동 실패 또는 공개 계약 파손을 재현할 때만 현재 PR을 막는다. 그 외 P2와 모든 P3는 소유자·수용 기준이 있는 후속 이슈로 이관할 수 있다.
- 후속 이슈로 이관한 finding은 해결된 것으로 표시하지 않는다. 원 review thread에는 이관 근거와 이슈 링크를 남기고, 병합 판단에는 잔여 P0/P1과 CI 상태를 명시한다.
- 같은 원인의 review-fix loop가 두 번을 넘거나 90분을 초과하면 새 finding을 더 쫓기 전에 구조적 원인, 최소 병합 단위, 후속 이슈 경계를 다시 정한다.
- 통합 실패가 선행 계약 결함이면 후속 레인에서 우회하지 않고 선행 이슈를 다시 연다.
- Discord webhook은 작업 가시화 수단이며 완료·검증 근거를 대체하지 않는다.
- 공유 계약이 확정되기 전 후속 구현은 adapter 또는 feature boundary 안에 둔다.

## 9. Priority calculation

P0 착수·리뷰·병합 순서는 느낌이나 이슈 번호가 아니라 아래 점수와 선행 의존성으로 계산한다.

```text
priority score = 3G + 2D + 2E + R - C
```

- `G` (0~3): 지금 막힌 golden-path 구간의 심각도
- `D` (0~3): 이 작업이 해제하는 후속 작업 수와 계약 fan-out
- `E` (0~2): 발표 가능한 실제 동작·증거의 결손
- `R` (0~2): 보안, 개인정보, 데이터 무결성, 복구 위험
- `C` (1~3): 완료까지의 상대 비용과 불확실성

점수가 같으면 선행 의존성, 더 많은 팀원을 해제하는 작업, 더 짧게 검증 가능한 작업 순으로 먼저 한다. 점수는 업무 가치를 고정하는 숫자가 아니라 현재 병목을 설명하는 운영 도구이므로 GitHub 상태가 바뀔 때 다시 계산한다.

| 순서 | 작업 | G | D | E | R | C | 점수 | 현재 판단 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| done | PR #26 rebase와 Identity ingress 통합 | 3 | 3 | 2 | 2 | 2 | **19** | PR #26을 최신 main 기반으로 복구·검증해 병합했다. |
| 1 | #27 Identity issuance integration (PR #51) | 3 | 3 | 2 | 2 | 2 | **19** | #36의 소비자 검증 경계와 #26의 browser session 위에 user `battle.play`, Identity service-token issuance, Battle acquisition과 deploy key/cookie policy를 연결했다. |
| 2 | #15 authenticated realtime seam과 Judge correlation | 3 | 3 | 2 | 2 | 3 | **18** | outbound authorization/fan-out/replay는 #38, commands/fencing은 #40/#42, autonomous deadline driver는 #44로 병합됐다. #51의 발급·획득 계약 위에 accepted-submission correlation과 judged-event 종료 처리를 연결한다. |
| 3 | #16 scoring, rating, result commit | 3 | 2 | 2 | 2 | 3 | **16** | 결과·전적의 producer이며 #17과 #18의 실제 결과 화면보다 먼저 고정한다. |
| 3 | #17 public feed와 result projection | 3 | 2 | 2 | 1 | 2 | **16** | public result post를 완성하되 producer event가 확정되기 전 cross-DB 우회는 금지한다. |
| 3 | PR #28 rebase와 #18 actual adapter 전환 | 3 | 2 | 2 | 1 | 2 | **16** | route shell은 병렬화하되 mock을 성공 기능으로 노출하지 않는다. |
| 4 | #19 acceptance evidence와 rehearsal | 2 | 2 | 2 | 1 | 1 | **14** | 구현과 병렬로 증거 표를 갱신하고 RC 동결 뒤 전체 흐름을 실행한다. |
| backlog | #32 legacy canonicalization hardening | 0 | 0 | 0 | 1 | 1 | **0** | P1 follow-up이다. P0 golden path 회귀가 관측되지 않는 한 현재 critical path를 선점하지 않는다. |
| backlog | [#35](https://github.com/SJY051/D3/issues/35) ranked replay/queue hardening | 0 | 0 | 0 | 1 | 1 | **0** | PR #33의 non-blocking P2 세 건이다. 정상 golden path 또는 공개 상태를 손상하는 재현이 생기기 전에는 #27/#15 통합을 선점하지 않는다. |
| backlog | [#43](https://github.com/SJY051/D3/issues/43) JUDGING socket-close semantics | 0 | 0 | 0 | 1 | 2 | **-1** | PR #42의 non-blocking P2다. pending Judge outcome 정책과 함께 결정하며 현재 deadline driver를 선점하지 않는다. |
| backlog | [#45](https://github.com/SJY051/D3/issues/45) deadline polling indexes | 0 | 0 | 0 | 1 | 1 | **0** | PR #44의 non-blocking P2다. 대표 데이터의 query-plan evidence와 forward-only migration으로 처리한다. |

## 10. Immediate assignment queue

1. **임수혁 (`GledoubleN`):** PR #26을 최신 `main`에 rebase하고 V1을 다시 쓰지 않은 채 migration/security 충돌을 해결한다. 재검증 전 기존 CI 성공을 현재 통과 증거로 재사용하지 않는다.
2. **윤서진:** fresh Judge0 host-local 6-runtime smoke 54/54와 issue #59 production-adapter source-SG private-route smoke를 완료했다. 매일 23:55 정지와 다음 운영일 09:00 시작 결과를 확인하고, 실제 Judge HTTP service 배포는 별도 deployment evidence로 남긴다.
3. **윤서진:** #44로 autonomous deadline driver를 마감했다. #26의 발급 계약을 기다리는 동안 #16의 versioned scoring/rating 순수 도메인 계산과 고정 예제를 별도 PR로 착수한다.
4. **윤서진:** #26 rebase 뒤 Identity의 user `battle.play`와 Battle service-token 발급을 통합하고, 그 다음 Battle-to-Judge accepted-submission correlation을 연결한다. #35/#43/#45는 정상 golden path가 막히지 않는 한 이 통합 뒤에 처리한다.
5. **박주형 (`david3123123`):** PR #28을 최신 main에 rebase하고 공통 API adapter, 상태 UI, P1 mock 비노출을 확인한다. #18 전체 완료가 아니라 부분 구현으로 증거를 남긴다.
6. **최정민:** WF-01~WF-06의 승인/수정/deviation 상태와 #19 acceptance matrix·발표 동선을 정리한다. 휴가 복귀 후 실제 QA owner와 GitHub assignee를 맞춘다.
7. **임수혁:** PR #31을 최신 `main`에 rebase해 public post/feed slice를 마감하고, projection은 versioned producer event 확정 후 연결한다. #26과 #31의 migration 충돌을 한 번에 섞지 않는다.
8. **전원:** P0/P1과 8절의 차단 조건을 재현한 P2는 현재 PR에서 해결한다. 그 외 P2/P3만 후속 이슈로 이관해 WIP 1개 원칙을 지킨다.

## 11. Change control

- 담당자 또는 critical-path 순서 변경은 팀 합의 후 lead가 이 문서와 GitHub assignee를 함께 갱신한다.
- 각 상태 변경에는 날짜와 이슈/PR 링크를 남긴다.
- 완료된 항목은 근거 명령, 테스트, trace, recording 중 하나 이상을 연결한다.
- 문서와 GitHub 상태가 다르면 GitHub의 현재 상태를 먼저 확인하고 같은 작업에서 문서를 고친다.
- 이 문서는 계획을 승인하는 기록이지 commit, push, merge, deploy 권한을 자동으로 부여하지 않는다.
