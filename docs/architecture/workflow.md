# Ranked match workflow

Owner: 최정민  
Status: Initial baseline  
Requirements: D3-BTL-001 through D3-BTL-005, D3-JDG-001, D3-STAT-001

```mermaid
flowchart LR
  subgraph PlayerA["Player A"]
    A1["Sign in"] --> A2["Choose language"]
    A3["Code / run / submit"]
    A4["Review result"]
  end

  subgraph PlayerB["Player B"]
    B1["Sign in"] --> B2["Choose language"]
    B3["Code / run / submit"]
    B4["Review result"]
  end

  subgraph Battle["Battle service"]
    Q["Match queue"] --> R["Authoritative room"]
    R --> O["Outcome and rating"]
  end

  subgraph Judge["Judge service"]
    J1["Accept job"] --> J2["Isolated execution"] --> J3["Classified result"]
  end

  subgraph Community["Community projection"]
    C1["Result post"]
    C2["Searchable record"]
  end

  A2 --> Q
  B2 --> Q
  R --> A3
  R --> B3
  A3 --> J1
  B3 --> J1
  J3 --> O
  O --> A4
  O --> B4
  O --> C1
  O --> C2
```

The final swimlane shall add owner actions, error branches, reconnect, surrender, and the platform-incident void path after the first vertical slice is observable.

