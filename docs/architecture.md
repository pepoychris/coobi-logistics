---
layout: page
title: "Architecture in one minute"
description: "How the simulator, Kafka, Kafka Streams, PostgreSQL and the Vue console fit together."
---

# Architecture in one minute

Coobi Logistics is a small, observable event pipeline: a simulator publishes vehicle telemetry, Kafka carries it, Kafka Streams derives state and alerts, PostgreSQL keeps the read model, and a Vue/Three.js dashboard reads the result over REST + SSE.

```mermaid
flowchart LR
  G[Event generator\nJava 21] -->|vehicle.location.v1| K[(Kafka)]
  K --> P[Stream processor\nKafka Streams]
  P -->|latest state + alerts| DB[(PostgreSQL)]
  P -->|Actuator + Prometheus metrics| PR[Prometheus]
  DB --> A[Logistics API\nREST + SSE]
  A --> F[Vue fleet console\nThree.js map]
  PR --> F
```

## The event path

```mermaid
sequenceDiagram
  participant S as Simulator
  participant K as Kafka
  participant T as Topology
  participant D as PostgreSQL
  participant A as Logistics API
  participant B as Browser
  S->>K: VehicleLocationEvent (key = vehicleId)
  K->>T: validate once
  T-->>K: invalid -> DLQ
  T->>D: latest vehicle state
  T-->>K: alert event on logistics.alert.v1
  B->>A: REST snapshot + SSE connection
  A->>D: paged read model
  A-->>B: bounded events/statistics stream
```

## Why these boundaries

- Kafka absorbs bursts and preserves per-vehicle key ordering.
- Kafka Streams owns stateful transitions: speeding and prolonged stops.
- PostgreSQL is a query-friendly read model, not a telemetry archive.
- SSE gives the browser live updates without exposing Kafka or sending every raw record.
- Prometheus measures the real path; benchmark claims are backed by captured runs in [`docs/benchmarks.md`](benchmarks.md).

## Where it runs

Everything described here is the local stack started by `docker compose up --build`. The
published ports are bound to `127.0.0.1`, and the repository configures no tunnel, no
domain and no public ingress, so Kafka, PostgreSQL, Prometheus and the Actuator endpoints
are reachable only from the machine that runs the stack.

See [`events.md`](events.md) for contracts and [`deployment.md`](deployment.md) for local startup.
