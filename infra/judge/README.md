# Judge0 operations and evidence

Owner: 윤서진

Status: Dedicated host and six-language runtime matrix active

Last verified: 2026-08-14 in account `811221506617`, region `ap-northeast-2`

Requirements: D3-JDG-001, D3-UX-002, D3-SEC-001, M-04, M-10

Judge0 is a private execution dependency of Judge service. Browser, Gateway, Battle, and Community must never call it directly. The real Judge adapter must normalize its response behind [`contracts/http/judge.openapi.json`](../../contracts/http/judge.openapi.json); activation of this host does not claim that application integration is complete.

## Bound deployment

| Binding | Verified value |
|---|---|
| AWS account / region | `811221506617` / `ap-northeast-2` |
| Instance | `i-0981ab438329d3e62`, `t3.large` |
| Base image | Canonical Ubuntu 22.04 amd64, `ami-012a353bb3afb92ee` |
| Root volume | Encrypted 40 GiB `gp3`, deleted with the instance |
| Network | VPC `vpc-0fed27f3f40fdcd72`, subnet `subnet-0928189f3c272e682` |
| Security group | `sg-0e3253c9132787639` (`d3-judge0`), zero ingress rules |
| Instance role | `D3-Judge0-EC2-Role` via `D3-Judge0-EC2-Profile` |
| Secret | `d3/judge0/api-auth-token` in Secrets Manager; value never enters the repository or operator output |
| Judge0 release | Judge0 CE `1.13.1` |
| Release archive SHA-256 | `d83f002c98f2c3935fc4fec3abcb863191e7c863ffcd202401d03a8f7e1fa513` |

The role has `AmazonSSMManagedInstanceCore` and one inline `secretsmanager:GetSecretValue` permission scoped to `d3/judge0/api-auth-token-*`. There is no SSH key pair or inbound SSH rule. IMDSv2 is required with hop limit 1; instance metadata tags are disabled.

The current subnet assigns a public IPv4 address for outbound bootstrap because this VPC has no private egress path. Zero security-group ingress prevents the internet from reaching the API, and the 2026-08-14 external `:2358` probe timed out. This is an activation compromise, not the final private-subnet target. Before allowing Judge service traffic, add a source-security-group-only rule on the private address or move the host behind a reviewed private egress path; never add a public CIDR rule.

## Pinned containers

| Component | Immutable image |
|---|---|
| Judge0 server and workers | `judge0/judge0@sha256:6b5d6a66aa19a8e878a52ea3c6a560afc1086734d96e2885b561fd5c6018f082` |
| PostgreSQL | `postgres@sha256:07572430dbcd821f9f978899c3ab3a727f5029be9298a41662e1b5404d5b73e0` |
| Redis | `redis@sha256:9341b6548cc35b64a6de0085555264336e2f570e17ecff20190bf62222f2bd64` |

The Judge0 release archive and all three image digests are fixed. Changing any value invalidates the runtime matrix and requires the smoke suite again.

## Runtime and request boundary

| Setting | Active value |
|---|---:|
| API authentication | Custom header/token from Secrets Manager |
| Wait result, callbacks, batched submissions, deletion | Disabled |
| Compiler options, command arguments, additional files | Disabled |
| Submission network | Disabled and caller opt-in forbidden |
| CPU time | 2 s default, 10 s maximum; the maximum also bounds compilation |
| Extra / wall time | 0.5 s default and maximum / 5 s default, 15 s maximum |
| Memory / stack | 262,144 KiB / 65,536 KiB maximum |
| Processes or threads | 60 maximum |
| File and archive extraction | 1,024 KiB each maximum |
| Repeated runs | 3 maximum |
| Workers / queue | 2 / 20 |
| Host cgroup mode | v1, required by this Judge0 release |

Host-wide outbound access remains available for SSM, Secrets Manager, package retrieval, and image bootstrap. Isolation is enforced separately inside Judge0 with `ENABLE_NETWORK=false` and `ALLOW_ENABLE_NETWORK=false`; this distinction must not be replaced with an inaccurate claim that the EC2 host has no egress.

## Runtime matrix

This matrix comes from the authenticated `/languages` response and command `00f2045c-40aa-4ac6-a630-527d30acc2fa`, which ran [`smoke.sh`](smoke.sh) on 2026-08-14 after binding the repository startup overlays.

| Product language | Judge0 ID | Observed runtime | Hello-world | Deterministic case |
|---|---:|---|---|---|
| C | 50 | GCC 9.2.0 | PASS | PASS |
| C++ | 54 | GCC 9.2.0 | PASS | PASS |
| Java | 62 | OpenJDK 13.0.1 | PASS | PASS |
| Python 3 | 71 | Python 3.8.1 | PASS | PASS |
| JavaScript | 63 | Node.js 12.14.0 | PASS | PASS |
| TypeScript | 74 | TypeScript 3.7.4 | PASS | PASS |

The TypeScript compiler did not finish inside a two-second compilation envelope. Execution defaults remain two CPU seconds, while `MAX_CPU_TIME_LIMIT=10` and `MAX_WALL_TIME_LIMIT=15` give compiled runtimes a bounded build envelope. The future Judge adapter must continue to submit the product's stricter per-problem execution limits.

## Startup log hardening

Judge0 CE 1.13.1's bundled server and worker scripts pipe the complete exported environment through `tee`, which places API, database, Redis and Rails secrets in container logs. D³ mounts [`startup/server`](startup/server) and [`startup/workers`](startup/workers) through [`docker-compose.override.yml`](docker-compose.override.yml). The overlay suppresses stdout while retaining the root-readable `/api/environment` file with mode `0640`.

The initially exposed generated values were rotated, the empty bootstrap database volume was recreated, and old containers were removed before smoke execution. Post-smoke validation reported zero secret-pattern and zero known-source lines in server/worker logs. The bound startup files match repository SHA-256 values `6a10c0fbec18ecbf57460df7d9a943cbdccd4f8111418dc8791ce974d4bc83d7` and `0fff12be85fff1141805a859f895a10370d3e23de55c1df3e2d330e90f9028af`. Do not run the unmodified upstream startup commands on this host.

## Sanitized smoke

Run [`smoke.sh`](smoke.sh) on the instance through SSM. It refuses non-loopback endpoints by default, reads credentials only from environment variables, emits no source, compiler output, hidden input, API credential, or submission token, and deletes its temporary response files.

On the instance, obtain the authentication values without printing them, export the six IDs from the verified matrix, and run the script:

```bash
secret_json="$(aws secretsmanager get-secret-value \
  --region ap-northeast-2 \
  --secret-id d3/judge0/api-auth-token \
  --query SecretString \
  --output text)"
export JUDGE0_AUTH_HEADER="$(jq -r .header <<<"$secret_json")"
export JUDGE0_AUTH_TOKEN="$(jq -r .token <<<"$secret_json")"
unset secret_json

export D3_JUDGE_C_ID=<verified-id>
export D3_JUDGE_CPP_ID=<verified-id>
export D3_JUDGE_JAVA_ID=<verified-id>
export D3_JUDGE_PYTHON3_ID=<verified-id>
export D3_JUDGE_JAVASCRIPT_ID=<verified-id>
export D3_JUDGE_TYPESCRIPT_ID=<verified-id>

./smoke.sh
unset JUDGE0_AUTH_HEADER JUDGE0_AUTH_TOKEN
```

The suite proves unauthenticated rejection, authenticated version/worker discovery, network opt-in rejection, a blocked outbound socket from executed code, six hello-world executions, the same deterministic sum problem in all six languages, and wrong-answer, compilation, runtime, timeout, and memory-pressure behavior. Judge0 reports memory pressure as a bounded `Runtime Error (NZEC)` at exactly 65,536 KiB for the smoke request; the Judge adapter owns normalization to D³ `MEMORY_LIMIT`. A live infrastructure outage is not induced by this suite; `PLATFORM_FAILURE` normalization is covered by the deterministic adapter test from PR #20.

## Start, stop, and inspect

Authenticate with the team profile first and verify the exact account as described in [`docs/operations/aws-cli-setup.md`](../../docs/operations/aws-cli-setup.md).

```bash
aws ec2 describe-instances --profile d3 --region ap-northeast-2 \
  --instance-ids i-0981ab438329d3e62 \
  --query 'Reservations[0].Instances[0].State.Name' --output text

aws ec2 start-instances --profile d3 --region ap-northeast-2 \
  --instance-ids i-0981ab438329d3e62

aws ec2 stop-instances --profile d3 --region ap-northeast-2 \
  --instance-ids i-0981ab438329d3e62
```

Use SSM Run Command to inspect `systemctl status d3-judge0` and `docker-compose -f /opt/d3/judge0/docker-compose.yml ps`. Never place the authentication token in an SSM command parameter, GitHub issue, log, or screenshot; fetch it from Secrets Manager inside the instance.

Stop the instance when the team does not need live judging. Compute billing stops while it is stopped, but the EBS volume and Secrets Manager secret remain billable.

## Cleanup boundary

Cleanup is destructive and requires a separate decision that this exact host is no longer needed. Resolve the exact resource IDs above, capture final evidence, and then remove in this order:

1. Terminate only `i-0981ab438329d3e62` and confirm its root volume was deleted.
2. Delete `sg-0e3253c9132787639` after the instance detaches.
3. Remove `D3-Judge0-EC2-Profile`, its role association, the role's inline policy, and `D3-Judge0-EC2-Role`.
4. Schedule `d3/judge0/api-auth-token` for recoverable deletion with a reviewable recovery window.

Do not delete the shared VPC, subnet, route table, internet gateway, account-level IAM users, or unrelated resources.

## Evidence and remaining gaps

| Check | 2026-08-14 result |
|---|---|
| Account, region, instance, AMI, encrypted disk, role | PASS |
| Zero ingress and external API probe | PASS |
| IMDSv2 and secret read scope | PASS |
| Digest-pinned containers and cgroup v1 | PASS |
| API auth and request/resource limits | PASS |
| Submission network isolation | PASS: opt-in HTTP 422 and executed outbound socket blocked |
| Six-language hello-world and deterministic sum | PASS: 12/12 cases |
| Accepted, wrong answer, compilation, runtime, timeout, memory | PASS |
| Platform failure normalization | PASS in PR #20 fake-adapter test; live outage injection NOT RUN |
| Runtime log privacy | PASS after overlay and secret rotation: 0 secret/source matches |
| Reboot persistence | PASS: systemd active, cgroup v1, repository overlay hashes, authenticated Python execution, and zero secret log matches |
| Real Judge service adapter | NOT IMPLEMENTED; tracked by issue #13 |
| Designated-host performance calibration | NOT RUN; no performance claim |

Primary upstream references: [Judge0 repository](https://github.com/judge0/judge0), [Judge0 changelog](https://github.com/judge0/judge0/blob/master/CHANGELOG.md), and [Judge0 API documentation](https://github.com/judge0/judge0/blob/master/public/docs.html).
