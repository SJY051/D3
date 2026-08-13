# CI/CD boundary

Owner: 윤서진
Status: Pull-request CI baseline; container publication and deployment blocked
Requirements: D3-QLT-001, D3-SEC-001

## Active CI

`.github/workflows/ci.yml` runs for every pull request and every push to `main`. Concurrent runs for the same ref are cancelled so that only the newest evidence remains active. The workflow token has read-only repository contents access, checkout does not persist credentials, and the final delivery-boundary job has no token permissions.

The required jobs are intentionally separate:

| Job | Required evidence | What it does not prove |
|---|---|---|
| Repository checks | Frozen pnpm install, high-severity dependency audit, scaffold, contract, and agent-configuration validation | Implemented product behavior |
| Frontend build and test evidence | Type check, unit-test inventory, production build, and browser-test inventory | A skipped unit or browser scenario is not a passing scenario |
| Backend build and test evidence | Gradle wrapper validation, compilation, packaging, and JUnit result collection | A disabled JUnit test is not behavior evidence; no external service is started |
| Infrastructure configuration | Default and observability Compose models parse and resolve | Containers start, become healthy, or integrate successfully |
| Delivery boundary | All four required jobs completed successfully | Container build, publication, deployment, or runtime smoke testing |

The `Delivery boundary (no deploy)` job is the stable aggregation gate for branch protection and the attachment point for a future container build or CD workflow. It has no checkout, credentials, cloud permissions, registry login, or deployment command.

## Reproducibility and provenance

- Jobs use the GitHub-hosted Ubuntu 24.04 runner label instead of the moving `ubuntu-latest` label.
- Node.js comes from `.node-version`, pnpm is fixed to the version in `package.json`, and installation uses `pnpm-lock.yaml` with `--frozen-lockfile`.
- Java uses Temurin 21. Gradle runs through the checked-in wrapper, whose distribution version and SHA-256 checksum are fixed in `gradle/wrapper/gradle-wrapper.properties`.
- GitHub actions are pinned to full commit SHAs. Version comments are human-readable labels, not resolution inputs.
- Dependency caches are performance inputs only. Pull requests may read the Gradle cache but cannot publish Gradle cache entries for trusted branches.
- Compose service images are already selected by immutable digest in `infra/compose.yaml`; CI validates configuration without pulling or starting them.

Action upgrades must resolve the release tag in the action's official repository, review the release provenance, and replace both the full SHA and its version comment in one change.

## Test and report evidence

Frontend unit and browser command output is uploaded as `frontend-test-evidence`. Gradle XML and HTML test reports are uploaded as `backend-test-evidence`. Both artifacts are retained for seven days and are uploaded even when a preceding test command fails, when files exist.

These artifacts expose passed, failed, and skipped counts for review. The current scaffold contains explicitly skipped frontend, browser, and backend requirement tests. A green command containing skips proves only that the inventory was collected. It cannot satisfy the corresponding D3-QLT-001 behavior requirement.

Rendered Compose configuration, environment files, source archives, credentials, and private user-code data are not CI artifacts. Infrastructure-dependent Testcontainers, real-Judge, live browser golden-path, and service-startup smoke tests become required only when their implementations and runtime dependencies are activated; until then they are not-run evidence, not implicit passes.

## Local workflow-equivalent checks

Run these from the repository root with Node.js 24.19.0, pnpm 11.9.0, Java 21, Gradle through `./gradlew`, and a Docker API/Compose-compatible runtime:

```bash
pnpm install --frozen-lockfile
pnpm audit --audit-level high
pnpm verify:scaffold
pnpm web:typecheck
pnpm web:test
pnpm web:build
pnpm web:e2e
./gradlew build --no-daemon
docker compose -f infra/compose.yaml config --quiet
docker compose -f infra/compose.yaml --profile observability config --quiet
```

Report command outcomes as pass, fail, skip, and not-run separately. A local run does not reproduce GitHub-hosted runner isolation, permissions, action execution, or artifact upload.

## Future container delivery

CI deliberately stops before a container build because the repository does not yet define reviewed, reproducible application-image inputs. The assigned AWS account and Seoul region are confirmed, but the IAM boundary, registry, deployment environment, OIDC role, and approval identity are not. Adding a registry login or a nominal deploy step before those bindings exist would misrepresent CD readiness.

Activate container delivery in a separate, reviewed change only after the deployment-plan bindings are resolved. The intended boundary is:

1. Require the active CI aggregation gate for the exact source revision.
2. Build each approved application image once from pinned base and build inputs.
3. Scan the image and retain an SBOM and provenance record.
4. Publish by immutable digest to the assigned registry; never deploy a mutable tag.
5. Require the protected environment's approval, then obtain narrowly scoped cloud credentials through GitHub OIDC.
6. Deploy the reviewed digest, run environment smoke checks, and retain a tested rollback path.

The future workflow must grant `id-token: write` only to the deployment job that needs OIDC. Pull-request jobs must never receive cloud credentials, push images, mutate GitHub settings, create environments, or deploy to AWS.
