# System context

Owner: 윤서진  
Status: Initial baseline

```mermaid
flowchart TB
  Browser["React web client"] --> Gateway["API gateway"]
  Gateway --> Identity["Identity service"]
  Gateway --> Battle["Battle service"]
  Gateway --> Community["Community service"]
  Battle -->|"job acceptance"| Judge["Judge service"]
  Judge -->|"private API"| Judge0["Isolated Judge0"]

  Identity --> IdentityDb[("Identity PostgreSQL")]
  Battle --> BattleDb[("Battle PostgreSQL")]
  Judge --> JudgeDb[("Judge PostgreSQL")]
  Community --> CommunityDb[("Community PostgreSQL")]
  Battle --> Redis[("Ephemeral Redis")]

  Identity --> Kafka[("Kafka")]
  Judge --> Kafka
  Battle --> Kafka
  Kafka --> Battle
  Kafka --> Community

  Config["Config server"] -.-> Identity
  Config -.-> Battle
  Config -.-> Judge
  Config -.-> Community
  Discovery["Discovery server"] -.-> Gateway
```

The browser never calls Judge0 or a service database directly. Managed AWS products are deployment choices for these boundaries, not additional domain owners.

