# AWS activation boundary

Terraform modules are deferred until the bootcamp account supplies region, IAM boundary, VPC/subnet policy, quotas, and permitted services. Creating placeholder resources before those bindings would produce misleading architecture evidence.

The intended first deployment maps the documented service boundaries to ECS, PostgreSQL to RDS, Redis to ElastiCache, Kafka to MSK only if the assigned quota permits it, and Judge0 to an isolated EC2 instance. S3 and CloudFront remain optional until real image or attachment traffic exists.

When bindings arrive, record them in an issue, add remote-state and least-privilege decisions, run `terraform fmt`, `terraform validate`, and a reviewed plan, and require separate authority before apply.
