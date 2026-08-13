# D³

**Dopamin-Driven Development** is a developer microblog and competitive programming game prototype.

> Scaffold status: structure and explicit skipped tests exist; product behavior is not implemented yet.

## Start here

- Product contract: [`docs/specs/d3-mvp.md`](docs/specs/d3-mvp.md)
- Low-fidelity wireframes: [`docs/wireframes/README.md`](docs/wireframes/README.md)
- Artifact status: [`docs/artifact-status.md`](docs/artifact-status.md)
- Contribution workflow: [`CONTRIBUTING.md`](CONTRIBUTING.md)
- Local infrastructure: [`infra/README.md`](infra/README.md)
- GitHub and Discord activation: [`docs/operations/collaboration.md`](docs/operations/collaboration.md)

## Toolchain

- Java 21
- Spring Boot 4.0.7 and Spring Cloud 2025.1.2
- Gradle wrapper with Kotlin DSL
- Node.js 24.19.0 and pnpm 11.9.0
- Docker API and Docker Compose compatible runtime

## Repository map

```text
apps/web/                 React route shell
services/                 Identity, battle, judge, and community boundaries
platform/                 Discovery, configuration, and gateway applications
contracts/                Versioned HTTP, event, and WebSocket contracts
infra/                    Local Compose and future cloud activation points
docs/                     Specifications and submission artifact sources
.agents/skills/            Canonical project skills
.claude/skills/            Claude Code adapters
.codex/hooks.json          Codex project hook (requires local trust review)
```

## Structural checks

```bash
pnpm install
pnpm verify:scaffold
pnpm audit --audit-level high
pnpm web:typecheck
pnpm web:test
pnpm web:build
pnpm web:e2e
./gradlew build
docker compose -f infra/compose.yaml config
```

These commands validate the scaffold and collect disabled behavior tests. They do not prove the MVP works.
