# D³

**Dopamin-Driven Development**는 개발자 마이크로블로그와 1:1 경쟁 프로그래밍을 하나의 경험으로 잇는 부트캠프 프로토타입입니다. 핵심 시연 경로는 로그인한 두 사용자가 랭크 매치에서 코드를 제출하고, 서버가 결과를 확정한 뒤 평점·RP·커뮤니티 전적에 반영하는 과정입니다.

Owner: D³ team

Status: Local runtime active; P0 golden path rehearsed with the local fake judge

Last verified: 2026-08-18 against main `35ee063`, CI 5/5 and the isolated two-browser rehearsal evidence

> **현재 상태 (2026-08-18): 로컬 플랫폼 활성, P0 골든 패스 리허설 실증.** 격리된 fresh-volume 환경에서 두 브라우저 세션으로 회원가입·로그인부터 피드 게시·교차 조회, 동일 언어 랭크 매칭, Battle v3 제출·재접속·종료, viewer-relative 결과·전적과 자동 결과 게시까지 확인했습니다. 판정은 로컬의 결정론적 fake judge를 사용했으므로 실제 코드 실행 증거가 아닙니다. `rating.changed.v1` 두 건과 Community `profile_projection`의 rating-first 반영도 확인했습니다. 다만 fresh DB용 시연 문제 seed가 없어 리허설에서는 수동 INSERT 1행을 사용했으며 [Issue #73](https://github.com/SJY051/D3/issues/73)에서 추적합니다. handle 검색과 Identity `user-profile.changed.v1` 투영은 미구현이고, 수동 DB 편집 없는 최종 two-session 인수는 [Issue #19](https://github.com/SJY051/D3/issues/19)에서 PENDING입니다. 상세 상태와 필요한 증거는 [산출물 현황](docs/artifact-status.md)을 따릅니다.

## 왜 D³인가

일반적인 문제 풀이 플랫폼의 결과를 일회성 점수로 끝내지 않고, 개발자의 공개 정체성과 커뮤니티 활동으로 연결합니다.

- **경쟁 게임:** 서버 권위의 1:1 코딩 대전, 재접속·항복·가역 공격, 판정 근거 기반 결과
- **성장 지표:** 공개 rating, 시즌 RP, tier와 검색 가능한 전적을 분리해 표현
- **개발자 커뮤니티:** Markdown·코드 블록을 지원하는 피드와 랭크 결과 자동 게시
- **공통 판정 경계:** 랭크 대전과 솔로 연습이 같은 언어·판정 계약을 사용

구현 계약과 P0/P1 경계는 [MVP 명세](docs/specs/d3-mvp.md), 화면 우선순위는 [저해상도 와이어프레임](docs/wireframes/README.md)에 있습니다.

## 현재 저장소가 증명하는 것

| 영역 | 현재 증거 | 판정 |
|---|---|---|
| 제품 요구사항 | 16개 요구사항 ID와 4개 관찰 시나리오 | 초기 기준선 |
| Web | 승인된 WF-01/02/03/05/06 API-backed 화면(PR #28): 인증, 피드, 랭크 큐, 대전, 결과·전적 | 두 브라우저 리허설 PASS; 최종 two-session 인수는 Issue #19 PENDING |
| Backend | Identity 등록·세션, Battle 매칭·실시간 대전·결과 확정, Judge 판정 경계, Community 피드·전적·rating projection 수직 경로 | 로컬 fake judge 기반 골든 패스 리허설 PASS; handle 검색·Identity 프로필 투영 미구현 |
| Contracts | HTTP 4개, event 5개, WebSocket 1개 문서 | 골든 패스의 Identity·Battle·Judge·Community HTTP, Battle WebSocket, `rating.changed.v1` 경로 실증; 전체 계약 인수는 PENDING |
| Data | 서비스별 PostgreSQL 소유권, 논리 ERD와 forward-only Flyway 체인 | 네 서비스 fresh-install/upgrade migration PASS; 서비스 간 DB 공유 없음 |
| Local infra | PostgreSQL, Redis, Kafka, Config, Discovery, Gateway, 네 서비스와 Web | 전체 로컬 기동 및 dependency preflight PASS |
| Quality | 요구사항 스캐폴드, CI 5/5와 격리된 두 브라우저 리허설 | 로컬 플랫폼과 fake-judge 골든 패스 PASS; 문제 seed 없는 fresh DB 인수는 PENDING |
| Cloud/Judge0 | 전용 zero-ingress Judge0 호스트와 고정 6개 런타임; real adapter 코드 경로 | 호스트 PASS; 애플리케이션 사설 연결 PENDING/NOT RUN |

## 아키텍처

브라우저는 API Gateway만 호출하고, identity·battle·judge·community는 각자의 PostgreSQL 데이터와 공개 계약을 소유합니다. PostgreSQL이 내구 상태의 기준이며 Redis는 만료 가능한 조정 상태에만 사용합니다. Judge0는 judge-service 뒤의 격리된 실행 경계입니다.

- [시스템 컨텍스트](docs/architecture/system-context.md)
- [서비스·통신 경계](docs/architecture/services.md)
- [랭크 매치 워크플로](docs/architecture/workflow.md)
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

## 로컬 시작

### 사전 조건

- Java 21
- Node.js 24 (`.node-version`)
- pnpm 11 (`packageManager`는 `pnpm@11.9.0`)
- Docker API와 Compose 명세를 지원하는 컨테이너 런타임

AWS 작업을 맡은 팀원은 장기 Access Key 대신 [프로젝트용 AWS CLI `d3` 프로필](docs/operations/aws-cli-setup.md)을 설정하고, 작업 세션마다 지정 계정과 IAM 사용자임을 확인합니다. 로컬 개발과 저장소 검증에는 AWS 로그인이 필요하지 않습니다.

### 의존성과 공용 인프라

```bash
cp .env.example .env
pnpm install --frozen-lockfile
docker compose -f infra/compose.yaml up -d postgres redis kafka
docker compose -f infra/compose.yaml ps
```

`.env.example`의 값은 로컬 전용 기본값입니다. 공유·배포 환경의 비밀로 재사용하지 않습니다. 컨테이너 구성과 선택 프로필은 [인프라 안내](infra/README.md)를 확인합니다.

### Web 개발 서버 확인

```bash
pnpm --filter @d3/web dev
```

Vite가 출력한 로컬 주소에서 `/feed`, `/ranked`, 대전, 결과·전적 등 승인된 API-backed 화면을 확인할 수 있습니다. 개발 서버만 기동한 경우 API 요청에는 별도로 실행 중인 로컬 플랫폼이 필요합니다.

### 전체 로컬 런타임

```bash
pnpm local:start
```

이 명령은 공용 인프라가 healthy가 될 때까지 기다리고, 애플리케이션 JAR 빌드, Config·Discovery 선행 기동, Web·Gateway·도메인 서비스 기동과 `demo:preflight`까지 순서대로 수행합니다. 다른 로컬 스택과 격리하려면 `COMPOSE_PROJECT_NAME=d3checkpoint pnpm local:start`처럼 Compose project 이름을 지정할 수 있습니다. 네 서비스의 JDBC URL은 공통 `D3_POSTGRES_HOST`와 `D3_POSTGRES_PORT`에서 생성됩니다. 모든 로컬 JVM은 기본적으로 `127.0.0.1`에만 바인딩되고 Eureka에도 같은 loopback 주소를 광고합니다. `READY` 후 `Ctrl+C`는 애플리케이션 프로세스를 종료하고 데이터 컨테이너는 유지합니다. 인프라는 필요할 때 `docker compose -f infra/compose.yaml stop`으로 중지합니다.

judge-service는 로컬에서 결정론적 fake adapter를 기본으로 사용하며, 이는 API·영속성·이벤트 개발용이지 실제 코드 실행 증거가 아닙니다. real Judge0 adapter는 명시적으로 선택하고 사설 연결 preflight를 통과한 환경에서만 live 증거로 기록합니다. 또한 fresh DB에는 demonstration problem seed가 없어 랭크 매칭이 진행되지 않으므로, 수동 DB 편집 없는 시작 절차는 [Issue #73](https://github.com/SJY051/D3/issues/73)이 해결될 때까지 PENDING입니다.

## 검증

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

`PASS`, `FAIL`, `SKIP`, `NOT RUN`을 구분해 기록합니다. 현재 `web:test`, `web:e2e`와 여러 Java 테스트는 의도적으로 비활성화되어 있으며, 이 결과는 기능 통과가 아닙니다. 테스트 계층과 완료 증거는 [테스트 계획](docs/quality/test-plan.md)을 따릅니다.

## 팀 작업 흐름

1. 요구사항 ID와 관찰 가능한 완료 증거를 적은 이슈를 만들고 담당자를 지정합니다.
2. `<type>/<issue-number>-<short-slug>` 브랜치에서 한 가지 관심사만 변경합니다.
3. 관련된 좁은 검사와 전체 스캐폴드 검사를 실행하고 결과를 분리해 기록합니다.
4. PR에 요구사항, 계약 영향, 스크린샷 또는 로그, 위험과 후속 작업을 남깁니다.
5. CI, GitHub Codex 리뷰와 사람 리뷰를 거친 뒤 squash merge합니다.

세부 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md), GitHub·Discord 활성화는 [협업 운영 문서](docs/operations/collaboration.md), 클라우드 작업자 인증은 [AWS CLI 설정 가이드](docs/operations/aws-cli-setup.md)를 참고합니다.

## 제출·발표 자료

- [산출물 현황과 증거](docs/artifact-status.md)
- [제출 체크리스트](docs/requirements/submission-checklist.md)
- [발표 개요](docs/presentation/outline.md)
- [데모 런북](docs/operations/demo-runbook.md)
- [배포 계획](docs/operations/deployment-plan.md)
- [보안 검토 경계](docs/quality/security-review.md)

자유 주제의 정확한 채점표, AWS 배포 역할·관리형 서비스·호스트 바인딩, 최종 화면·성능 수치는 아직 확정 증거가 없으므로 `UNKNOWN`입니다.
