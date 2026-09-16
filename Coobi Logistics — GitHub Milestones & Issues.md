# Coobi Logistics — Implementation Roadmap

## Project Goal

Build a production-oriented real-time logistics event processing platform demonstrating:

- Modern Java
- Spring Boot
- Apache Kafka
- Kafka Streams
- Event-driven architecture
- PostgreSQL
- Vue 3 + TypeScript
- Server-Sent Events
- Docker / Docker Compose
- Testcontainers
- Prometheus / Micrometer
- High-throughput event processing
- Performance benchmarking

The system simulates a logistics fleet producing vehicle telemetry events. These events are sent through Kafka, processed in real time, analyzed for relevant conditions, persisted when appropriate, and visualized through a live web dashboard.

---

# Global Implementation Rules

These rules apply to every milestone and every issue.

1. Use the latest stable Java version officially supported by the selected stable Spring Boot release.
2. Use the latest stable Spring Boot release available at implementation time.
3. Prefer Java records for immutable DTOs and event payloads when appropriate.
4. Use constructor injection. Do not use field injection.
5. Keep domain logic independent from infrastructure whenever practical.
6. Do not introduce technologies not explicitly requested by the current milestone.
7. Do not implement future milestones in advance.
8. Do not expose Kafka or PostgreSQL publicly.
9. Do not hardcode credentials or environment-specific configuration.
10. Every service must provide meaningful structured logs.
11. Every service must fail fast when mandatory configuration is invalid.
12. All timestamps must use UTC.
13. All Kafka events must contain a unique `eventId`.
14. Kafka event schemas must be versioned.
15. `vehicleId` must be used as the Kafka message key for vehicle-specific events.
16. Tests must be added alongside functionality rather than postponed until the end.
17. Update the README whenever a milestone changes how the application is executed.
18. `docker compose up --build` must remain the canonical local startup command once Docker support is introduced.
19. Do not implement authentication, Kubernetes, Redis, Schema Registry, Avro, geofencing, route management, users, roles or other features outside the defined MVP.
20. Prefer simple, maintainable solutions over unnecessary abstractions.

---

# Repository Structure

Target structure:

```text
coobi-logistics-streaming/
├── services/
│   ├── event-generator/
│   ├── stream-processor/
│   └── logistics-api/
├── frontend/
├── infrastructure/
│   ├── prometheus/
│   ├── docker/
│   └── scripts/
├── docs/
│   ├── architecture.md
│   ├── events.md
│   └── benchmarks.md
├── compose.yml
├── compose.prod.yml
├── .env.example
├── README.md
└── LICENSE
```

---

# Milestone MVP-0 — Project Foundation

## Goal

Create the repository foundation and local infrastructure required by subsequent milestones.

The milestone is complete when Kafka and PostgreSQL can be started locally and the repository provides a clear foundation for the three backend services.

---

## Issue MVP-0.1 — Initialize repository structure

### Description

Create the initial monorepo structure.

### Requirements

Create:

```text
services/
frontend/
infrastructure/
docs/
```

Create placeholders for:

```text
services/event-generator
services/stream-processor
services/logistics-api
```

Add:

```text
.gitignore
.editorconfig
.env.example
README.md
LICENSE
```

The `.gitignore` must cover:

- Java
- Maven/Gradle
- IntelliJ
- VS Code
- Node
- Vue
- environment files
- build artifacts
- logs

### Acceptance Criteria

- Repository follows the agreed structure.
- `.env` is ignored.
- `.env.example` is committed.
- No credentials exist in the repository.
- README explains the project's purpose and current development status.

---

## Issue MVP-0.2 — Create Docker infrastructure

### Description

Create the local Kafka and PostgreSQL infrastructure using Docker Compose.

### Requirements

Services:

```text
kafka
postgres
```

Kafka must operate in KRaft mode if supported by the selected Kafka image/configuration.

Initial development configuration:

```text
Kafka:
  broker count: 1

PostgreSQL:
  database: logistics
  user: configurable
  password: configurable
```

Do not expose services unnecessarily beyond localhost.

### Acceptance Criteria

Running:

```bash
docker compose up -d
```

starts Kafka and PostgreSQL successfully.

Both services must include health checks where practical.

No passwords are hardcoded in `compose.yml`.

---

## Issue MVP-0.3 — Define project configuration

### Description

Create a consistent environment-variable-based configuration strategy.

### Required variables

```text
KAFKA_BOOTSTRAP_SERVERS
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD

VEHICLE_COUNT
TARGET_EVENTS_PER_SECOND

SPEED_LIMIT
STOPPED_WINDOW_SECONDS
MOVEMENT_THRESHOLD_METERS
```

Provide safe development defaults in `.env.example` where appropriate.

### Acceptance Criteria

- Configuration is documented.
- Secrets are not committed.
- Services will be able to override configuration through environment variables.

---

# Milestone MVP-1 — Kafka Event Producer

## Goal

Implement a configurable vehicle telemetry simulator capable of producing a significant number of realistic events to Kafka.

At the end of this milestone:

```text
Event Generator → Kafka
```

must work reliably.

---

## Issue MVP-1.1 — Create event-generator Spring Boot service

### Description

Create the `event-generator` Spring Boot application.

### Dependencies

Include only what is required:

- Spring Boot
- Spring Kafka
- Validation
- Actuator
- testing dependencies

### Requirements

Application configuration must support:

```text
KAFKA_BOOTSTRAP_SERVERS
VEHICLE_COUNT
TARGET_EVENTS_PER_SECOND
```

Expose:

```text
/actuator/health
```

### Acceptance Criteria

- Application starts successfully.
- Application connects to Kafka.
- Health endpoint reports the service state.
- Configuration can be overridden using environment variables.

---

## Issue MVP-1.2 — Define vehicle telemetry event contract

### Description

Implement the initial versioned Kafka event model.

### Event

`VehicleLocationEvent`

Required fields:

```json
{
  "eventId": "UUID",
  "eventType": "VEHICLE_LOCATION_UPDATED",
  "version": 1,
  "vehicleId": "TRUCK-00001",
  "timestamp": "UTC timestamp",
  "data": {
    "latitude": 39.4699,
    "longitude": -0.3763,
    "speed": 82.3,
    "heading": 214.5
  }
}
```

### Validation

At minimum:

```text
latitude: -90..90
longitude: -180..180
speed: >= 0
heading: 0..<360
vehicleId: non-empty
eventId: non-null
timestamp: non-null
```

### Acceptance Criteria

- Event is immutable.
- Event is JSON serializable.
- Schema version is present.
- Unit tests cover validation and serialization.

---

## Issue MVP-1.3 — Create Kafka topics

### Description

Configure the initial Kafka topics.

Create:

```text
logistics.vehicle.location.v1
logistics.vehicle.location.dlq.v1
```

Development configuration:

```text
partitions = 6
replication-factor = 1
```

Topic creation must be reproducible.

### Acceptance Criteria

- Topics exist automatically after infrastructure startup or application initialization.
- Location topic has six partitions.
- Topic configuration is documented.

---

## Issue MVP-1.4 — Implement realistic vehicle simulator

### Description

Implement simulated vehicles producing evolving telemetry.

Each simulated vehicle must maintain state:

```text
vehicleId
latitude
longitude
speed
heading
```

Successive positions must evolve gradually.

Do NOT generate completely random coordinates for every event.

Use a defined geographical starting area suitable for demonstration purposes.

### Acceptance Criteria

- Vehicle IDs are deterministic and unique.
- A vehicle does not teleport randomly between unrelated locations.
- Speed changes gradually.
- Heading changes gradually.
- Coordinates remain valid.
- Simulator works with at least 1,000 vehicles.

---

## Issue MVP-1.5 — Implement configurable Kafka event publishing

### Description

Publish generated telemetry to:

```text
logistics.vehicle.location.v1
```

Kafka key:

```text
vehicleId
```

Support:

```text
NORMAL
LOAD_TEST
```

Normal default:

```text
1000 vehicles
1000 events/sec target
```

Load test values must be configurable.

### Acceptance Criteria

- Events continuously reach Kafka.
- `vehicleId` is the Kafka key.
- Target event rate can be configured.
- Producer failures are logged.
- Application shuts down gracefully.
- Basic producer throughput is measurable.

---

# Milestone MVP-2 — Real-Time Stream Processing

## Goal

Introduce the core Kafka Streams processing service.

At the end:

```text
Generator
   ↓
Kafka
   ↓
Stream Processor
   ↓
processed events / alerts
```

must work.

---

## Issue MVP-2.1 — Create stream-processor service

### Description

Create a Spring Boot Kafka Streams application.

### Responsibilities

Consume:

```text
logistics.vehicle.location.v1
```

Validate events and prepare the topology for stateful processing.

### Acceptance Criteria

- Service connects to Kafka.
- Kafka Streams topology starts successfully.
- Events are consumed.
- Processing errors are logged appropriately.
- Health endpoint is available.

---

## Issue MVP-2.2 — Implement invalid-event handling and DLQ

### Description

Invalid telemetry events must not silently disappear or crash the stream.

Invalid events must be published to:

```text
logistics.vehicle.location.dlq.v1
```

DLQ event must include:

```text
originalEvent
error
failedAt
sourceTopic
```

### Acceptance Criteria

- Invalid coordinates reach DLQ.
- Invalid vehicle IDs reach DLQ.
- Valid events continue processing after an invalid event.
- Tests verify DLQ behavior.

---

## Issue MVP-2.3 — Implement speeding detection

### Description

Detect vehicles exceeding:

```text
SPEED_LIMIT
```

Default:

```text
120 km/h
```

Create topic:

```text
logistics.alert.v1
```

Generate:

```text
SPEEDING
```

alerts.

### Important behavior

Do not generate one alert for every telemetry event while the vehicle remains above the speed limit.

Implement state transitions:

```text
NORMAL
   ↓ speed > limit
SPEEDING
   ↓ speed <= limit
NORMAL
```

Only transition into `SPEEDING` produces an alert.

### Acceptance Criteria

- Crossing the threshold generates exactly one alert.
- Remaining above the threshold does not generate duplicates.
- Returning below and later exceeding the threshold creates a new alert.
- Behavior is covered by tests.

---

## Issue MVP-2.4 — Define alert event contract

### Event

```json
{
  "eventId": "UUID",
  "eventType": "SPEEDING_DETECTED",
  "version": 1,
  "vehicleId": "TRUCK-00182",
  "severity": "WARNING",
  "timestamp": "...",
  "data": {
    "speed": 137.2,
    "threshold": 120
  }
}
```

Supported MVP types:

```text
SPEEDING
VEHICLE_STOPPED
```

### Acceptance Criteria

- Alerts are immutable.
- Alerts have unique event IDs.
- Events are versioned.
- Serialization tests exist.

---

# Milestone MVP-3 — Stateful Stream Processing

## Goal

Demonstrate meaningful stateful Kafka Streams processing.

---

## Issue MVP-3.1 — Maintain current vehicle state

### Description

Maintain the latest known telemetry state for each vehicle.

Key:

```text
vehicleId
```

State must contain:

```text
latitude
longitude
speed
heading
lastUpdate
status
```

Use Kafka Streams state facilities where appropriate.

### Acceptance Criteria

- Latest state can be reconstructed per vehicle.
- Events remain ordered per vehicle partition.
- State survives normal stream processing lifecycle according to Kafka Streams guarantees.
- State behavior is tested.

---

## Issue MVP-3.2 — Detect stopped vehicles

### Description

Detect vehicles that have remained effectively stationary for a configurable period.

Defaults:

```text
STOPPED_WINDOW_SECONDS=300
MOVEMENT_THRESHOLD_METERS=50
```

Conceptually:

```text
Vehicle positions
       ↓
temporal/state analysis
       ↓
movement < 50m for 5 min
       ↓
VEHICLE_STOPPED
```

Distance calculation must account for geographic coordinates appropriately, e.g. Haversine distance.

### Requirements

As with speeding:

```text
MOVING → STOPPED
```

must generate one alert.

Remaining stopped must not continuously generate alerts.

Moving again resets the state.

### Acceptance Criteria

- Stopped vehicle produces one alert.
- Continued stopped telemetry does not duplicate alerts.
- Moving vehicle resets stopped status.
- Thresholds are configurable.
- Tests use deterministic timestamps rather than real waits.

---

# Milestone MVP-4 — Persistence Layer

## Goal

Persist useful derived state without turning PostgreSQL into an event store.

Do NOT store every telemetry event.

---

## Issue MVP-4.1 — Integrate PostgreSQL

### Description

Connect the processing/backend components to PostgreSQL.

Use migrations through Flyway or Liquibase. Prefer Flyway unless there is a strong reason otherwise.

### Acceptance Criteria

- Database schema is created through migrations.
- No Hibernate auto-create/update is used for production schema management.
- Database credentials come from configuration.

---

## Issue MVP-4.2 — Create vehicle tables

Create:

### `vehicles`

```text
id
vehicle_id
created_at
```

Constraints:

```text
vehicle_id UNIQUE
```

### `vehicle_latest_state`

```text
vehicle_id
latitude
longitude
speed
heading
status
last_update
```

### Acceptance Criteria

- Latest vehicle state is persisted.
- Updating telemetry updates existing state rather than inserting unlimited history.
- Appropriate indexes exist.

---

## Issue MVP-4.3 — Persist alerts idempotently

Create:

```text
alerts
```

Fields:

```text
id
event_id
vehicle_id
type
severity
created_at
metadata
```

Constraint:

```text
event_id UNIQUE
```

### Acceptance Criteria

Given:

```text
Alert A
Alert A
Alert A
```

database contains exactly one alert.

Reprocessing Kafka messages must not create duplicate persistent alerts.

---

# Milestone MVP-5 — Logistics REST API

## Goal

Expose processed information through a clean HTTP API.

---

## Issue MVP-5.1 — Create logistics-api service

Create a Spring Boot REST application.

Base path:

```text
/api/v1
```

Dependencies:

- Spring Web
- Spring Data JPA
- Validation
- Actuator
- PostgreSQL driver
- Flyway if migrations are owned by this service

### Acceptance Criteria

- Application starts independently.
- PostgreSQL connection works.
- `/actuator/health` is available.

---

## Issue MVP-5.2 — Vehicle endpoints

Implement:

```text
GET /api/v1/vehicles
GET /api/v1/vehicles/{vehicleId}
```

Vehicle list must support pagination.

Optional query parameters:

```text
status
```

### Acceptance Criteria

- Pagination works.
- Unknown vehicle returns 404.
- Invalid parameters return 400.
- API responses do not expose JPA entities directly.

---

## Issue MVP-5.3 — Alert endpoints

Implement:

```text
GET /api/v1/alerts
GET /api/v1/alerts/{id}
```

Filters:

```text
vehicleId
type
severity
```

Pagination required.

### Acceptance Criteria

- Filters work independently.
- Pagination works.
- Unknown alert returns 404.
- DTOs are used.

---

## Issue MVP-5.4 — Statistics endpoint

Implement:

```text
GET /api/v1/statistics
```

Return:

```json
{
  "processedEvents": 12938281,
  "eventsPerSecond": 12492,
  "activeVehicles": 5000,
  "alertsGenerated": 281,
  "uptimeSeconds": 8271
}
```

Values must come from actual application metrics/state where possible.

Do not fabricate values.

### Acceptance Criteria

- Endpoint returns live values.
- Response is documented.
- Endpoint has automated tests.

---

# Milestone MVP-6 — Real-Time Browser Streaming

## Goal

Expose selected backend information to the browser in real time without sending the complete Kafka throughput to the client.

---

## Issue MVP-6.1 — Implement SSE event stream

Implement:

```text
GET /api/v1/stream/events
```

using Server-Sent Events.

Stream a sampled/throttled representation of interesting events.

### Critical requirement

If Kafka processes:

```text
25,000 events/sec
```

do NOT send 25,000 SSE messages/sec to every browser.

Introduce configurable throttling/sampling.

### Acceptance Criteria

- Browser can establish SSE connection.
- Connection remains alive.
- Disconnects are handled correctly.
- Event volume is bounded.
- Backend remains stable when no browser is connected.

---

## Issue MVP-6.2 — Implement real-time statistics stream

Implement:

```text
GET /api/v1/stream/statistics
```

Suggested update interval:

```text
1 second
```

Include:

```text
events/sec
processed events
active vehicles
alerts
```

### Acceptance Criteria

- Statistics update without browser polling.
- Connection recovery is supported by normal SSE behavior.
- Update rate is configurable.

---

# Milestone MVP-7 — Vue Real-Time Dashboard

## Goal

Create the public visual demo that recruiters can understand within seconds.

---

## Issue MVP-7.1 — Initialize Vue frontend

Stack:

```text
Vue 3
TypeScript
Vite
```

Requirements:

- API client
- environment-based API URL
- production build
- basic responsive layout

Do not introduce a large UI framework unless necessary.

---

## Issue MVP-7.2 — Create KPI dashboard

Display:

```text
Events processed
Events/sec
Active vehicles
Alerts generated
System status
```

Values must come from the backend.

### Acceptance Criteria

- KPIs update in real time.
- Loading state exists.
- Disconnected/error state exists.
- No fake statistics are displayed.

---

## Issue MVP-7.3 — Add live vehicle map

Use:

```text
Leaflet
OpenStreetMap
```

Display active vehicles.

Vehicle markers should update without recreating the entire map.

Clicking a vehicle should display:

```text
vehicleId
speed
status
last update
```

### Acceptance Criteria

- Map remains responsive with a representative number of vehicles.
- Markers update incrementally.
- Vehicle data comes from the backend.
- No paid map API is required.

---

## Issue MVP-7.4 — Add live event feed

Display recent events:

```text
12:32:01 TRUCK-21 LOCATION_UPDATED
12:32:02 TRUCK-92 SPEEDING
12:32:05 TRUCK-42 VEHICLE_STOPPED
```

Keep only a bounded number in browser memory.

Suggested:

```text
100 events maximum
```

### Acceptance Criteria

- New events appear automatically.
- Old events are removed.
- Alerts are visually distinguishable from normal telemetry.
- Feed does not cause unbounded browser memory growth.

---

# Milestone MVP-8 — Observability

## Goal

Make performance and system behavior measurable.

---

## Issue MVP-8.1 — Add Micrometer metrics

Expose Prometheus-compatible metrics.

Required custom metrics:

```text
logistics_events_received_total
logistics_events_processed_total
logistics_events_failed_total
logistics_alerts_generated_total
logistics_event_processing_duration
```

Use appropriate metric types:

```text
Counter
Timer
Gauge
```

### Acceptance Criteria

- Metrics are real.
- Metrics are documented.
- Prometheus can scrape them.

---

## Issue MVP-8.2 — Add Prometheus

Add Prometheus to Docker Compose.

Configure scraping for relevant services.

### Acceptance Criteria

```text
docker compose up --build
```

starts Prometheus.

Prometheus reports relevant targets as healthy.

---

## Issue MVP-8.3 — Expose Kafka processing metrics

Ensure relevant Kafka producer/consumer/streams metrics are available.

Particularly useful:

```text
records/sec
processing rate
consumer lag where available
producer errors
```

### Acceptance Criteria

- Kafka processing behavior can be inspected.
- Metrics can be used during benchmark runs.

---

# Milestone MVP-9 — Testing & Reliability

## Goal

Provide meaningful automated evidence that the event-driven pipeline works.

---

## Issue MVP-9.1 — Complete unit test suite

At minimum test:

```text
event validation
vehicle simulation
speeding state transitions
stopped vehicle detection
distance calculations
DTO mapping
statistics calculations
```

### Acceptance Criteria

- Tests are deterministic.
- Tests do not depend on external Kafka/PostgreSQL.
- No `Thread.sleep(300000)`-style time-based tests.

---

## Issue MVP-9.2 — Kafka integration tests with Testcontainers

Use Testcontainers to start Kafka.

Verify:

```text
produce telemetry
       ↓
Kafka
       ↓
stream processor
       ↓
alert
```

### Acceptance Criteria

- Real Kafka is used in integration tests.
- Speeding flow is verified end-to-end.
- Invalid event/DLQ flow is verified.

---

## Issue MVP-9.3 — PostgreSQL integration tests

Use Testcontainers PostgreSQL.

Verify:

```text
vehicle state persistence
alert persistence
idempotency
database migrations
```

### Acceptance Criteria

Duplicate `eventId` must never result in duplicate alerts.

---

## Issue MVP-9.4 — Full pipeline smoke test

Create an automated smoke/integration scenario covering as much as practical:

```text
Generator/Event
       ↓
Kafka
       ↓
Processor
       ↓
PostgreSQL
       ↓
API
```

### Acceptance Criteria

A generated telemetry scenario can be observed through the public API after processing.

---

# Milestone MVP-10 — Single-Command Local Deployment

## Goal

Make the complete project trivial to evaluate locally.

---

## Issue MVP-10.1 — Complete Dockerfiles

Provide optimized multi-stage Dockerfiles for:

```text
event-generator
stream-processor
logistics-api
frontend
```

### Requirements

- Small runtime images where practical.
- Non-root execution where practical.
- Build dependencies excluded from runtime image.
- Health checks.

---

## Issue MVP-10.2 — Complete Docker Compose stack

The following command:

```bash
docker compose up --build
```

must start:

```text
Kafka
PostgreSQL
event-generator
stream-processor
logistics-api
frontend
Prometheus
```

### Acceptance Criteria

A new developer with Docker installed can clone the repository and run the application without manually installing:

```text
Java
Kafka
PostgreSQL
Node
```

---

## Issue MVP-10.3 — Add graceful startup and health dependencies

Avoid relying only on container startup order.

Applications must tolerate infrastructure initialization appropriately.

### Acceptance Criteria

- Restarting Kafka does not permanently break services.
- Restarting PostgreSQL does not require rebuilding containers.
- Compose health status is meaningful.
- Services shut down gracefully.

---

# Milestone MVP-11 — Performance Benchmark

## Goal

Produce reproducible evidence of system performance.

Performance numbers must never be invented.

---

## Issue MVP-11.1 — Create benchmark mode

Provide a reproducible way to configure:

```text
vehicle count
target events/sec
duration
```

Example:

```bash
VEHICLE_COUNT=10000 \
TARGET_EVENTS_PER_SECOND=25000 \
docker compose up --build
```

Benchmark mode should minimize irrelevant frontend influence on the measurement.

---

## Issue MVP-11.2 — Execute benchmark scenarios

Test at minimum:

| Vehicles | Target events/sec | Duration |
|---:|---:|---:|
| 1,000 | 1,000 | 5 min |
| 5,000 | 10,000 | 5 min |
| 10,000 | 25,000 | 5 min |
| 20,000 | 50,000 | 5 min |

If the machine cannot sustain a target, record that result rather than modifying or hiding it.

Collect:

```text
actual events/sec
processed events
failed events
p50 latency
p95 latency
p99 latency
consumer lag
CPU
RAM
```

Also record test hardware.

---

## Issue MVP-11.3 — Create benchmark report

Create:

```text
docs/benchmarks.md
```

Include:

```text
hardware
software versions
configuration
Kafka partitions
JVM settings
methodology
results
bottlenecks
observations
```

Example wording:

```text
Sustained XX,XXX events/sec on [hardware]
with p95 processing latency of XX ms.
```

Only include measured values.

---

# Milestone MVP-12 — Portfolio-Ready Release

## Goal

Turn the technical project into something that can be understood quickly by recruiters and engineers.

---

## Issue MVP-12.1 — Create architecture documentation

Create:

```text
docs/architecture.md
```

Include diagrams for:

```text
system architecture
Kafka event flow
stream processing
persistence
browser data flow
```

Prefer Mermaid diagrams so diagrams remain version-controlled.

---

## Issue MVP-12.2 — Document event contracts

Create:

```text
docs/events.md
```

Document:

```text
topics
keys
partitions
event schemas
versions
producer
consumer
failure handling
DLQ
ordering assumptions
```

---

## Issue MVP-12.3 — Rewrite README for portfolio

README opening must immediately explain the project.

Recommended structure:

```text
# Coobi Logistics

Real-time logistics event processing platform built
with Java, Spring Boot, Apache Kafka and Kafka Streams.

[Live Demo] [Architecture] [Benchmarks]

## Highlights

XX events/sec
XX simulated vehicles
XX ms p95 latency

## Architecture

## Why Kafka?

## Event Model

## Running Locally

## Observability

## Testing

## Performance

## Design Decisions

## Future Improvements
```

Do not include benchmark numbers until they have actually been measured.

---

## Issue MVP-12.4 — Prepare public deployment configuration

Prepare production configuration for:

```text
logistics.coobi.xyz
```

The initial deployment target is:

```text
Cloudflare
     ↓
Cloudflare Tunnel
     ↓
local Docker stack
```

Only expose the HTTP frontend/reverse proxy.

Never expose:

```text
Kafka
PostgreSQL
Prometheus administration
internal Actuator endpoints
```

### Acceptance Criteria

- Demo works over HTTPS.
- Domain points to the application.
- No infrastructure ports are publicly reachable.
- No Cloudflare tokens exist in Git.

---

# Post-MVP Backlog

These features must NOT be implemented until MVP-12 is complete.

---

## FUTURE-1 — Grafana

Create operational dashboards for:

```text
throughput
latency
consumer lag
alerts
JVM
```

---

## FUTURE-2 — Schema Registry

Introduce:

```text
Schema Registry
Avro or Protobuf
```

Provide backward-compatible schema evolution.

---

## FUTURE-3 — Geofencing

Add configurable geographic regions.

Generate:

```text
GEOFENCE_EXIT
GEOFENCE_ENTRY
```

events.

---

## FUTURE-4 — Outbox Pattern

Introduce the transactional outbox pattern where a service must reliably coordinate database changes and Kafka events.

Document the consistency problem it solves.

---

## FUTURE-5 — Horizontal stream processing

Run multiple `stream-processor` instances.

Study:

```text
partitions
consumer groups
rebalance behavior
horizontal scalability
```

Benchmark:

```text
1 processor
2 processors
3 processors
6 processors
```

---

## FUTURE-6 — OpenTelemetry

Introduce distributed telemetry and tracing.

---

## FUTURE-7 — CI/CD

GitHub Actions pipeline:

```text
compile
unit tests
integration tests
frontend build
Docker image build
security/dependency checks
```

Optionally publish versioned container images after successful releases.

---

## FUTURE-8 — VPS deployment

Production architecture:

```text
Internet
   ↓
Cloudflare
   ↓
VPS
   ↓
Reverse Proxy
   ↓
Docker
├── frontend
├── logistics-api
├── stream-processor
├── event-generator
├── Kafka
├── PostgreSQL
└── Prometheus
```

Kafka and PostgreSQL must remain private.

---

# Final Definition of Done

Version `1.0.0` is ready when all MVP-0 through MVP-12 milestones are complete and the following scenario works:

```text
1. Clone repository

2. Configure .env

3. Run:

   docker compose up --build

4. Event generator starts simulated vehicles.

5. Kafka receives telemetry.

6. Kafka Streams processes telemetry.

7. Speeding and stopped-vehicle conditions are detected.

8. Alerts are produced.

9. Invalid events reach DLQ.

10. Current vehicle state is persisted.

11. Alerts are persisted idempotently.

12. REST API exposes state.

13. SSE pushes real-time information.

14. Vue dashboard displays live metrics.

15. Vehicles move on the map.

16. Prometheus exposes system metrics.

17. Automated tests pass.

18. Benchmark results are reproducible.

19. README documents the architecture and results.

20. Public demo is accessible through logistics.coobi.xyz.
```

# Instructions for the AI Implementing the Project

When receiving an issue:

1. Read this global specification first.
2. Implement only the requested issue.
3. Inspect existing code before making architectural decisions.
4. Do not implement later issues proactively.
5. Preserve compatibility with completed milestones.
6. Add or update tests required by the issue.
7. Run relevant tests before considering the issue complete.
8. Update documentation when behavior/configuration changes.
9. Report all files created or modified.
10. Report commands executed and their results.
11. Report any assumption made.
12. Report any deviation from the specification and explain why.
13. Do not mark an issue complete if an acceptance criterion is not satisfied.
14. Do not replace real functionality with mocked behavior merely to satisfy acceptance criteria.
15. Do not invent benchmark or performance results.

At the end of each issue, return:

```text
## Implementation Summary

### Changes
- ...

### Tests
- ...

### Verification
- ...

### Assumptions
- ...

### Remaining Issues
- ...

### Acceptance Criteria
- [x] ...
- [ ] ...
```