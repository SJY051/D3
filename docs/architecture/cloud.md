# Cloud architecture

Owner: 윤서진  
Status: Awaiting AWS account binding

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

- Bind account, region, IAM boundary, quota, and allowed managed services before writing Terraform resources.
- Activate S3 and CloudFront only when a P1 upload requirement survives scope review.
- Keep Judge0 on isolated compute with no public submission endpoint and no execution egress.
- If managed-service provisioning is blocked, deploy the same application images to one application EC2 host with Compose while keeping Judge0 on a separate host.

