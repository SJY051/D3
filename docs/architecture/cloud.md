# Cloud architecture

Owner: 윤서진

Status: Account and region confirmed; IAM and service bindings pending

Last verified: 2026-08-14 against the assigned account, Seoul region and unresolved deployment bindings

```mermaid
flowchart TB
  User["Browser"] --> CDN["CloudFront or direct web endpoint"]
  CDN --> ALB["Application Load Balancer"]
  ALB --> ECS["ECS Fargate services"]
  ECS --> RDS[("RDS PostgreSQL")]
  ECS --> Cache[("ElastiCache Redis")]
  ECS --> MSK[("Amazon MSK")]
  ECS --> JudgeEC2["Private Judge0 EC2"]
  ECS -. "optional media" .-> S3[("S3")]
  ECR[("ECR")] --> ECS
  Secrets["Secrets Manager / Parameter Store"] --> ECS
  Metrics["CloudWatch / metrics export"] <-- ECS
```

## Activation gates

- The assigned account is `811221506617` in `ap-northeast-2`; bind its IAM boundary, quotas, and allowed managed services before writing Terraform resources.
- Activate S3 and CloudFront only when a P1 upload requirement survives scope review.
- Keep Judge0 on isolated compute with no public submission endpoint and no execution egress.
- If managed-service provisioning is blocked, deploy the same application images to one application EC2 host with Compose while keeping Judge0 on a separate host.
