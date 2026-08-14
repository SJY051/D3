# Infrastructure scaffold

`compose.yaml` provides only shared local dependencies. Application processes stay on the host so service logs and restarts remain easy to inspect.

```bash
cp .env.example .env
docker compose -f infra/compose.yaml up -d postgres redis kafka
docker compose -f infra/compose.yaml --profile observability up -d
```

From the repository root, the repeatable full-stack path is:

```bash
pnpm local:start
```

It starts the three default dependency containers, builds the application JARs, starts platform and application processes, starts Web, and runs the required preflight. Java processes and containers bind to loopback by default so the unauthenticated local Eureka registry is not shared with the surrounding network. `Ctrl+C` stops host processes; dependency containers remain available for the next run.

Judge0 and AWS are intentionally separate activation points. Read `judge/README.md` and `terraform/README.md` before adding either one.
