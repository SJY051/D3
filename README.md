# D³

**D³는 개발자 마이크로블로그(SNS)와 1:1 실시간 경쟁 코딩 배틀을 하나의 성장 경험으로 연결한 플랫폼입니다.**

Owner: D³ team

Status: Local runtime active; P0 golden path rehearsed with the local fake judge

Last verified: 2026-08-18 against the isolated two-browser rehearsal evidence

문제 풀이가 일회성 점수로 끝나면 실력의 변화와 개발자의 활동이 서로 단절됩니다. D³는 풀이와 대전 결과를 공개 피드, 전적, rating·RP로 이어 개발 과정 자체가 정체성과 기록이 되도록 만들었습니다.

일반적인 온라인 저지와 달리 동일 언어 사용자끼리 실시간으로 맞붙고, 서버가 Run·Submit·공격·재접속과 승패를 권위 있게 관리합니다. 확정된 결과는 자동으로 커뮤니티에 게시되어 경쟁과 공유가 하나의 흐름으로 남습니다.

**가입·로그인 → 공개 피드 게시·탐색 → 동일 언어 랭크 매칭 → 서버 권위 실시간 배틀(Run·Submit, 공격, 재접속) → 판정·승패 확정 → rating·RP 반영 → 자동 결과 게시·공개 전적**

<!-- 스크린샷 자리: docs/assets/ 아래 자산으로 내일 갱신 예정. -->

## Quick start

- 요구 사항: Java 21, Node.js 24, pnpm 11, Docker Compose
- `pnpm install`
- `pnpm local:start` — `demo-preflight: READY`가 표시되면 준비 완료 (V12 migration이 시연 문제를 자동 seed합니다)

시연 계정과 5분 시연 순서는 [데모 런북](docs/operations/demo-runbook.md)을 따릅니다.

## 문서 지도

전체 문서는 [문서 인덱스](docs/README.md)에서 목적별로 찾을 수 있습니다.

- [D³ 한 장 개요 — 처음이라면 여기부터](docs/overview.md)
- [MVP 요구사항·관찰 시나리오](docs/specs/d3-mvp.md)
- [랭크 매치 스윔레인](docs/architecture/workflow.md)
- [시스템 컨텍스트](docs/architecture/system-context.md)
- [산출물 현황과 증거](docs/artifact-status.md)

## 현재 증거 상태

현재 권위 있는 판정은 [산출물 현황](docs/artifact-status.md)을 따릅니다. 2026-08-18 기준 P0 골든 패스는 격리된 환경의 두 브라우저 세션과 로컬 결정론적 fake judge로 리허설했습니다. 회원가입·로그인, fenced code를 포함한 Markdown 피드, 동일 언어 매칭, Run·Submit·재접속, 결과·전적, rating·RP 반영과 자동 결과 게시까지 확인했습니다. 공격 교환과 Surrender는 라이브로 실증하지 않았으며 **NOT RUN**입니다.

fake judge 결과는 실제 코드 실행 증거가 아닙니다. Judge0 실행 호스트와 source-security-group 전용 사설 경로 자체는 격리된 AWS 환경에서 smoke로 검증했지만(issue #59), 배포된 judge-service가 그 경로로 실행하는 것은 **NOT RUN**입니다.

| 영역 | 현재 증거 | 판정 |
|---|---|---|
| 제품 요구사항 | 16개 요구사항 ID와 4개 관찰 시나리오 | 초기 기준선 |
| Web | 인증, 피드, 랭크 큐, 대전, 결과·전적 API-backed 화면 | 두 브라우저 리허설 PASS |
| Backend | Identity 등록·세션·프로필, Battle 매칭·실시간 대전·결과, Judge 판정 경계, Community 피드·전적·검색·rating projection | 로컬 fake judge 기반 골든 패스 PASS |
| Contracts | HTTP 4개, event 5개, WebSocket 1개 문서 | 골든 패스 계약 경로 실증; 전체 계약 인수 PENDING |
| Data | 서비스별 PostgreSQL 소유권, 논리 ERD와 forward-only Flyway 체인 | 네 서비스 migration PASS; 서비스 간 DB 공유 없음 |
| Local infra | PostgreSQL, Redis, Kafka, Config, Discovery, Gateway, 네 도메인 서비스와 Web | 전체 로컬 기동 및 dependency preflight PASS |
| Cloud/Judge0 | zero-ingress Judge0 호스트와 고정 6개 런타임, real adapter 코드 경로 | 호스트 PASS; 애플리케이션 사설 연결 PENDING/NOT RUN |

### 아키텍처와 저장소

브라우저는 API Gateway만 호출하고 identity·battle·judge·community는 각자의 PostgreSQL 데이터와 공개 계약을 소유합니다. PostgreSQL이 내구 상태의 기준이며 Redis는 만료 가능한 조정 상태에만 사용합니다. 도메인 이벤트는 outbox에서 Kafka로 발행되어 소비자의 inbox를 거치고, Judge0는 judge-service 뒤의 격리된 실행 경계에 둡니다.

- [서비스·통신 경계](docs/architecture/services.md)
- [논리 ERD](docs/architecture/erd.dbml)
- [클라우드 목표 구조](docs/architecture/cloud.md)
- [공개 계약 규칙](contracts/README.md)

```text
apps/web/                  React route shell
services/                  Identity, battle, judge, and community boundaries
platform/                  Discovery, configuration, and gateway applications
contracts/                 Versioned HTTP, event, and WebSocket contracts
infra/                     Local Compose and future cloud activation points
docs/                      Specifications and submission artifact sources
.agents/skills/            Canonical project skills
.claude/skills/            Claude Code adapters
.codex/hooks.json          Codex project hook; local trust review required once
```

### 로컬 런타임 세부사항

`pnpm local:start`는 공용 인프라의 health 확인, 애플리케이션 JAR 빌드, Config·Discovery 선행 기동, Web·Gateway·도메인 서비스 기동과 `demo:preflight`를 순서대로 수행합니다. 다른 로컬 스택과 격리하려면 `COMPOSE_PROJECT_NAME=d3checkpoint pnpm local:start`처럼 Compose project 이름을 지정합니다. 모든 로컬 JVM은 기본적으로 `127.0.0.1`에만 바인딩됩니다. `READY` 후 `Ctrl+C`는 애플리케이션 프로세스를 종료하고 데이터 컨테이너는 유지합니다.

로컬 judge-service는 API·영속성·이벤트 개발을 위한 deterministic fake adapter를 기본으로 사용합니다. real Judge0 adapter는 명시적으로 선택하고 사설 연결 preflight를 통과한 환경에서만 실제 실행 증거로 기록합니다. 컨테이너 구성과 선택 프로필은 [인프라 안내](infra/README.md)를 확인합니다.

AWS 작업자는 장기 Access Key 대신 [프로젝트용 AWS CLI `d3` 프로필](docs/operations/aws-cli-setup.md)을 사용합니다. 로컬 개발과 저장소 검증에는 AWS 로그인이 필요하지 않습니다.

### 검증 명령

```bash
pnpm verify:scaffold
pnpm audit --audit-level high
pnpm web:typecheck
pnpm web:test
pnpm web:build
pnpm web:e2e
./gradlew build
docker compose -f infra/compose.yaml config --quiet
docker compose -f infra/compose.yaml --profile observability config --quiet
```

`PASS`, `FAIL`, `SKIP`, `NOT RUN`을 구분해 기록합니다. 비활성화되거나 실행되지 않은 테스트는 기능 통과로 간주하지 않습니다. 테스트 계층과 완료 증거는 [테스트 계획](docs/quality/test-plan.md)을 따릅니다.

### 팀 작업 흐름

1. 요구사항 ID와 관찰 가능한 완료 증거를 적은 이슈에 담당자를 지정합니다.
2. `<type>/<issue-number>-<short-slug>` 브랜치에서 한 가지 관심사만 변경합니다.
3. 관련된 좁은 검사와 전체 스캐폴드 검사를 실행하고 결과를 분리해 기록합니다.
4. PR에 요구사항, 계약 영향, 스크린샷 또는 로그, 위험과 후속 작업을 남깁니다.
5. CI, GitHub Codex 리뷰와 사람 리뷰를 거친 뒤 squash merge합니다.

세부 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md), GitHub·Discord 활성화는 [협업 운영 문서](docs/operations/collaboration.md)를 참고합니다.

### 제출·발표 자료

- [제출 체크리스트](docs/requirements/submission-checklist.md)
- [발표 개요](docs/presentation/outline.md)
- [데모 런북](docs/operations/demo-runbook.md)
- [배포 계획](docs/operations/deployment-plan.md)
- [보안 검토 경계](docs/quality/security-review.md)

자유 주제의 정확한 채점표, 최종 화면과 성능 수치는 확정 증거가 없으므로 `UNKNOWN`입니다.
