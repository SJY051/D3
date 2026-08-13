# Cloud architecture

Owner: 윤서진

Status: Account, region and isolated Judge0 host bound; application service bindings pending

Last verified: 2026-08-14 against the assigned account, Seoul region and Judge0 host boundary

```mermaid
flowchart TB
  User["Browser"] --> CDN["CloudFront or direct web endpoint"]
  CDN --> ALB["Application Load Balancer"]
  ALB --> ECS["ECS Fargate services"]
  ECS --> RDS[("RDS PostgreSQL")]
  ECS --> Cache[("ElastiCache Redis")]
  ECS --> MSK[("Amazon MSK")]
  ECS -. "future source-SG-only path" .-> JudgeEC2["Zero-ingress Judge0 EC2"]
  SSM["AWS Systems Manager"] --> JudgeEC2
  ECS -. "optional media" .-> S3[("S3")]
  ECR[("ECR")] --> ECS
  Secrets["Secrets Manager / Parameter Store"] --> ECS
  Metrics["CloudWatch / metrics export"] <-- ECS
```

## Activation gates

- The assigned account is `811221506617` in `ap-northeast-2`; bind its IAM boundary, quotas, and allowed managed services before writing Terraform resources.
- Activate S3 and CloudFront only when a P1 upload requirement survives scope review.
- The Judge0 host is active on dedicated compute with zero ingress, SSM-only operation, API authentication, and disabled submission networking. Its current public subnet supplies bootstrap egress; no public API path is allowed. Bind a source-security-group-only Judge-service route or migrate it to a private subnet before application integration.
- If managed-service provisioning is blocked, deploy the same application images to one application EC2 host with Compose while keeping Judge0 on a separate host.
