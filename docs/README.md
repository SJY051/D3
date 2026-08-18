# D³ 문서 인덱스

Owner: Team

Status: Current documentation index; artifact completion follows the authoritative status inventory

Last verified: 2026-08-18

## 무엇을 찾고 있나요?

### 평가자이거나 프로젝트를 처음 보시나요?

1. [프로젝트 README](../README.md) — D³의 목적, 골든 패스, 현재 구현 범위와 실행 방법
2. [MVP 명세](specs/d3-mvp.md) — 핵심·선택 요구사항과 우선순위
3. [시스템 컨텍스트](architecture/system-context.md) — 사용자와 외부 시스템을 포함한 전체 경계
4. [산출물 상태](artifact-status.md) — 제출 산출물별 현재 상태와 남은 증거

### 개발자로 참여하나요?

1. [프로젝트 README](../README.md) — 개발 환경 요구사항과 로컬 실행 절차
2. [서비스 아키텍처](architecture/services.md) — 서비스 책임, 데이터 소유권과 연동 경계
3. [공개 계약](../contracts/README.md) — HTTP, WebSocket, Kafka 계약의 진입점
4. [테스트 계획](quality/test-plan.md) — 단위·통합·부하·장애 시험 범위와 증거 계획

### 운영하거나 시연해야 하나요?

1. [데모 런북](operations/demo-runbook.md) — 사전 점검, 계정 준비와 두 세션 시연 순서
2. [배포 계획](operations/deployment-plan.md) — 배포 절차, CI/CD와 롤백 계획
3. [클라우드 아키텍처](architecture/cloud.md) — AWS 경계와 현재 활성화 상태
4. [테스트 계획](quality/test-plan.md) — 운영 전 확인할 품질 게이트와 남은 검증

## 필수 산출물 10종

| # | 경로 | 한 줄 역할 | 대상 독자 | 현재 상태 요약 |
|---:|---|---|---|---|
| 1 | [`specs/d3-mvp.md`](specs/d3-mvp.md) | 핵심·선택 요구사항, 우선순위와 내부 평가 계획을 정의한다. | 평가자, 기획자, 개발자 | **Baseline** — 항목별 구현 증거와 최종 기준 확인이 남아 있다. |
| 2 | [`architecture/workflow.md`](architecture/workflow.md) | 랭크 매칭부터 판정·결과 반영까지의 스윔레인 흐름을 설명한다. | 평가자, 개발자 | **Target baseline** — 렌더 검토와 시나리오별 런타임 추적이 남아 있다. |
| 3 | [`architecture/system-context.md`](architecture/system-context.md) | D³와 사용자·외부 시스템 사이의 전체 경계를 보여 준다. | 평가자, 아키텍트 | **Baseline** — 실행 토폴로지와 최종 배포 구성이 남아 있다. |
| 4 | [`architecture/services.md`](architecture/services.md) | 서비스별 책임, 데이터 소유권과 이벤트·API 연동을 정리한다. | 개발자, 아키텍트 | **P0 주요 경로 구현** — 통합 브라우저 인가 증거가 남아 있다. |
| 5 | [`architecture/erd.dbml`](architecture/erd.dbml) | 서비스별 데이터 모델과 서비스 내부 참조를 DBML로 정의한다. | 개발자, DBA | **Logical baseline** — 32개 테이블·20개 내부 참조가 있으며 렌더와 운영 쿼리 검증이 남아 있다. |
| 6 | [`architecture/cloud.md`](architecture/cloud.md) | AWS 배치 경계와 Judge0 격리 실행 구성을 설명한다. | 운영자, 아키텍트 | **부분 검증** — Judge0 경로는 확인됐고 애플리케이션 서비스 배포는 남아 있다. |
| 7 | [`wireframes/README.md`](wireframes/README.md) | WF-01~WF-08 화면의 기능, 상태와 이동을 정의한다. | 평가자, 디자이너, 프런트엔드 개발자 | **부분 승인·구현** — WF-01~06 승인, 주요 P0 화면 병합; WF-07/08 검토와 통합 캡처가 남아 있다. |
| 8 | [`quality/test-plan.md`](quality/test-plan.md) | 단위·통합·부하·장애 시험의 범위와 증거 방식을 정의한다. | QA, 개발자, 운영자 | **Plan baseline + 일부 증거** — 전체 배포, 통합 UI, 부하·장애 보고가 남아 있다. |
| 9 | [`operations/deployment-plan.md`](operations/deployment-plan.md) | CI/CD, 배포 절차와 롤백 계획을 정리한다. | 운영자, 개발자 | **로컬 실행 가능** — 애플리케이션 이미지·AWS/OIDC 연동과 배포·롤백 리허설이 남아 있다. |
| 10 | [`../README.md`](../README.md), [`../contracts/README.md`](../contracts/README.md), 저장소 소스 | 프로젝트 소개·실행 안내와 공개 계약을 실제 구현 소스에 연결한다. | 모든 독자 | **실행 가능한 로컬 기여 기준선** — 최종 개정, 라이선스 결정과 릴리스 증거가 남아 있다. |

> 산출물의 소유자, 상태, 검증 시점과 남은 증거에 대한 권위 있는 기준은 [`artifact-status.md`](artifact-status.md)입니다.
