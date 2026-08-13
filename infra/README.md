# Infrastructure scaffold

`compose.yaml` provides only shared local dependencies. Application processes stay on the host during the initial vertical slice so service logs and restarts remain easy to inspect.

```bash
cp .env.example .env
docker compose -f infra/compose.yaml up -d postgres redis kafka
docker compose -f infra/compose.yaml --profile observability up -d
```

Judge0 and AWS are intentionally separate activation points. Read `judge/README.md` and `terraform/README.md` before adding either one.
