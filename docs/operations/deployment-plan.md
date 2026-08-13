# Deployment plan

Owner: 윤서진  
Status: Local baseline; AWS binding pending

## Local profiles

- Default: PostgreSQL databases, Redis, and Kafka.
- `observability`: Prometheus and Grafana.
- `judge`: reserved for a pinned self-hosted Judge0 bundle after its image and security configuration pass smoke review.
- Application processes run from the IDE or repository commands during normal development.

The project contract is the Docker API and Compose specification. Docker Desktop is the current macOS runtime; another compatible runtime may be used when it passes Compose and Testcontainers checks.

## Cloud target

- Application containers: ECS Fargate behind an ALB.
- Images: ECR, published only after a verified `main` build.
- Durable data: RDS PostgreSQL with service-specific databases and credentials.
- Ephemeral data: ElastiCache Redis.
- Events: Amazon MSK.
- User-code execution: separate private Judge0 EC2.
- Optional media: S3 presigned upload and CloudFront only after P1 activation.
- Secrets: Secrets Manager or Parameter Store; CI authentication through GitHub OIDC.

## Fallback

When managed services or permissions are unavailable, run application images through Compose on one application EC2 host while preserving a separate Judge0 host. Document every substituted managed boundary in the presentation.

## Unresolved bindings

- AWS account, region, IAM boundary, quotas, DNS, certificate, and allowed service classes.
- Instance and managed-service sizes after load and budget measurement.
- Terraform state backend and deployment approval identity.

