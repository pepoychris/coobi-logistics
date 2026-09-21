# Coobi Logistics

Real-time logistics event processing platform built with Java, Spring Boot, Apache Kafka
and Kafka Streams.

## Purpose

Coobi Logistics simulates a vehicle fleet that continuously produces telemetry events
(position, speed and heading) and runs them through an event-driven pipeline. Events are
published to Kafka, processed in real time, analyzed for relevant conditions such as
speeding or prolonged stops, persisted when they matter and visualized through a live web
dashboard.

The project is a portfolio-grade demonstration of event-driven architecture, stateful
stream processing and sustained high-throughput ingestion.

## Current Status

**MVP-4 - Derived state persisted in PostgreSQL.**

The repository foundation is in place (MVP-0.1), the environment contract is defined
(MVP-0.3): the complete set of environment variables, their safe development defaults
and their units are documented in `.env.example` and in the Configuration section
below. The local Docker infrastructure is in place (MVP-0.2): `compose.yml` starts a
single-node Kafka broker in KRaft mode and PostgreSQL, and both publish their ports on
the loopback interface only.

The event generator is implemented (MVP-1): `services/event-generator` is a Spring Boot
service that simulates a fleet of vehicles and publishes versioned location telemetry to
Kafka, with a configurable target rate, reproducible topics and a health endpoint. See
[docs/event-generator.md](docs/event-generator.md).

The stream processor is implemented (MVP-2 to MVP-4): `services/stream-processor` is a
Spring Boot Kafka Streams service that consumes that telemetry, validates it, routes invalid
records to the dead letter topic, maintains the latest state of every vehicle in a Kafka
Streams state store, turns speed-limit crossings and prolonged stops into alert events and
persists that derived state in PostgreSQL - one row per vehicle plus one row per accepted
alert, created by Flyway migrations and written idempotently.
See [docs/stream-processor.md](docs/stream-processor.md).

The REST API and the frontend arrive in the following milestones, which are tracked in the
implementation roadmap as milestones and issues.

## Configuration

Configuration is environment-variable based. `.env.example` is the source of truth for
the environment contract: the variable names, their development defaults, their units
and their scope. This section is a synchronized reference to that file and documents
the same contract. `.env` is the local, untracked file that holds actual values:

```powershell
Copy-Item .env.example .env
```

```bash
cp .env.example .env
```

Most defaults in `.env.example` are safe values for local development, and none of
them is a production value. `POSTGRES_PASSWORD` ships with no value at all, so a local
`.env` must supply one before any stack starts.

`KAFKA_BOOTSTRAP_SERVERS=localhost:9092` is the default for services launched from the
host, such as a service started from Maven or the IDE. A service running inside a
container needs its own network override, `kafka:29092`, which is the in-network
listener defined by `compose.yml` (see Local Infrastructure below).

| Variable | Purpose | Default | Units |
| --- | --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap servers, as reachable from a service launched from the host | `localhost:9092` | comma-separated `host:port` list |
| `POSTGRES_DB` | Database name used by the stack | `logistics` | database name |
| `POSTGRES_USER` | Database role used by the stack | `logistics` | role name |
| `POSTGRES_PASSWORD` | Database password for `POSTGRES_USER` | none - must be set in `.env` | password string |
| `VEHICLE_COUNT` | Simulated vehicles kept active by the event generator | `1000` | vehicles (count) |
| `TARGET_EVENTS_PER_SECOND` | Target telemetry publication rate of the event generator | `1000` | events per second |
| `GENERATOR_MODE` | Operating profile of the event generator: `NORMAL` or `LOAD_TEST` | `NORMAL` | enum |
| `GENERATOR_PUBLISH_ENABLED` | Whether the event generator publishes telemetry (the topics are still provisioned) | `true` | boolean |
| `COOBI_KAFKA_INITIALIZATION_ENABLED` | Whether the event generator provisions its Kafka topics on startup; `false` is the switch for starting without a broker | `true` | boolean |
| `LOAD_TEST_VEHICLE_COUNT` | Simulated vehicles used by the event generator in `LOAD_TEST` mode | `5000` | vehicles (count) |
| `LOAD_TEST_TARGET_EVENTS_PER_SECOND` | Target publication rate used by the event generator in `LOAD_TEST` mode | `20000` | events per second |
| `GENERATOR_RANDOM_SEED` | Seed of the deterministic trajectory generator | `20260101` | long |
| `SPEED_LIMIT` | Speed above which a vehicle is reported as speeding | `120` | kilometres per hour (km/h) |
| `STOPPED_WINDOW_SECONDS` | Time a vehicle must stay effectively stationary before it is reported as stopped | `300` | seconds |
| `MOVEMENT_THRESHOLD_METERS` | Total distance below which movement counts as "no movement" over the stopped window | `50` | metres |

Each service reads these variables from its own environment, and a value already
present in that environment wins over any fallback baked into the service. `compose.yml`
interpolates the `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` entries, so an
exported shell variable overrides the value copied into `.env`. Because Compose
interpolates `$` in `.env`, a literal dollar sign inside a value must be doubled:
`POSTGRES_PASSWORD=pa$$word` resolves to the value `pa$word`. The remaining variables
are consumed by the services: `KAFKA_BOOTSTRAP_SERVERS`, the vehicle simulator
variables and `GENERATOR_RANDOM_SEED` by the event generator (MVP-1), and `SPEED_LIMIT`,
`STOPPED_WINDOW_SECONDS` and `MOVEMENT_THRESHOLD_METERS` by the stream processor
(MVP-2 and MVP-3).

`.env` is listed in `.gitignore` and must never be committed. No credential is
versioned in this repository: `POSTGRES_PASSWORD` is documented with an empty value, and
you assign your own local password in `.env` before starting any stack. Compose resolves
that variable before it creates any container, so a missing or empty value aborts the
command instead of starting the stack with no password.

## Local Infrastructure

`compose.yml` at the repository root defines the local development stack:

```text
kafka     apache/kafka:4.3.1   single-node KRaft broker, no ZooKeeper
postgres  postgres:18.6        PostgreSQL
```

Both services publish their ports on the loopback interface only (`127.0.0.1:9092` and
`127.0.0.1:5432`), so the stack is never reachable from another machine.

Start the stack:

```powershell
Copy-Item .env.example .env
# open .env and set POSTGRES_PASSWORD to a local password
docker compose up --build -d
docker compose ps
```

```bash
cp .env.example .env
# open .env and set POSTGRES_PASSWORD to a local password
docker compose up --build -d
docker compose ps
```

`docker compose up --build -d` is the canonical local startup command (Global Rule 18).
The `--build` flag is currently a no-op, because no service in this stack ships a
Dockerfile yet, and it stays part of the command so that it keeps working once the
application services arrive. `docker compose up -d`, the MVP-0.2 acceptance criterion, is
the equivalent command today.

`docker compose ps` reports both services as `running` and then `healthy`. Compose reads
the `POSTGRES_*` values from `.env` or from the shell environment, and an exported shell
variable wins over `.env`. Compose resolves them before it creates any container, so a
missing or empty `POSTGRES_PASSWORD` aborts the command instead of starting the stack with
no password.

Stop the stack, keeping the local data in the named volumes:

```bash
docker compose down
```

Add `-v` to `docker compose down` to delete the `kafka-data` and `postgres-data` volumes
as well.

### Kafka endpoints

Kafka is published on two listeners, and the address to use depends on where the client
runs:

| Client | Bootstrap servers | Listener |
| --- | --- | --- |
| Service launched from the host (Maven or IDE) | `localhost:9092` | host listener, published on `127.0.0.1:9092` |
| Service running in a container on the Compose network | `kafka:29092` | in-network listener |

`KAFKA_BOOTSTRAP_SERVERS=localhost:9092` is the value for host-launched services, and a
containerized service overrides it with `kafka:29092`. The KRaft controller listener
(port `29093`) carries quorum traffic inside the broker container and is never
published. `CLUSTER_ID` is intentionally left unset: the official `apache/kafka` image
supplies a default storage cluster id, which is enough for this single local cluster, it
is not a secret, and the stack is not a multi-broker configuration.

### PostgreSQL

The database is reachable from the host at `localhost:5432` using the `POSTGRES_DB`,
`POSTGRES_USER` and `POSTGRES_PASSWORD` values from `.env`. A containerized service
reaches it at `postgres:5432`.

## Event Generator

`services/event-generator` simulates the fleet and produces the telemetry that the rest of
the pipeline consumes. It ships a Maven wrapper, so a local Maven installation is not
required, and it needs only a running Kafka broker:

```powershell
docker compose up -d
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
```

```bash
docker compose up -d
./services/event-generator/mvnw -f services/event-generator/pom.xml spring-boot:run
```

With the defaults it publishes 1,000 events per second from 1,000 simulated vehicles. The
documented variables are read from the process environment, so a local run can be
reconfigured without editing any file:

```powershell
$env:GENERATOR_MODE="LOAD_TEST"
$env:LOAD_TEST_TARGET_EVENTS_PER_SECOND="50000"
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
```

`GENERATOR_PUBLISH_ENABLED=false` stops the events but keeps the broker requirement: the
topics are still provisioned and the Kafka health indicator still runs. To start the
service with no Kafka at all, for example while working offline, disable provisioning as
well:

```powershell
$env:GENERATOR_PUBLISH_ENABLED="false"
$env:COOBI_KAFKA_INITIALIZATION_ENABLED="false"
$env:MANAGEMENT_HEALTH_KAFKA_ENABLED="false"   # /actuator/health stays UP without Kafka
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
```

The broker requirement stays in the shipped defaults, so a normal run fails fast when
Kafka is unreachable instead of publishing nowhere.

The wrapper needs no local Maven, but it downloads a Maven distribution on first use. If
`mvnw.cmd` fails under PowerShell 7, for example because of the wrapper download or a
proxy, an installed Maven is an equivalent fallback:
`mvn -f services/event-generator/pom.xml test` (or `spring-boot:run`).

| Item | Value |
| --- | --- |
| Event contract | `VehicleLocationEvent`, version 1, `eventType=VEHICLE_LOCATION_UPDATED` |
| Kafka key | `vehicleId` |
| Topics | `logistics.vehicle.location.v1`, `logistics.vehicle.location.dlq.v1` (6 partitions, replication factor 1) |
| Health | `GET http://localhost:8080/actuator/health` |
| Metrics | `GET http://localhost:8080/actuator/metrics/coobi.generator.events` |
| Tests | `.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml test` |

Topics are provisioned on startup and the operation is idempotent, so a repeated start
converges on the same topology. The simulator keeps per-vehicle state: positions, speeds
and headings evolve gradually from a seeded starting area, and they stay valid, which
means the data downstream services will process behaves like real telemetry instead of
uncorrelated random points.

Read [docs/event-generator.md](docs/event-generator.md) for the complete configuration
contract, the simulator model, the observability signals and the troubleshooting table.

## Stream Processor

`services/stream-processor` consumes the telemetry, validates it and produces alerts. It
ships a Maven wrapper and it provisions its topics on startup, so it needs only a running
Kafka broker:

```powershell
docker compose up -d
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml spring-boot:run
```

```bash
docker compose up -d
./services/event-generator/mvnw -f services/event-generator/pom.xml spring-boot:run
./services/stream-processor/mvnw -f services/stream-processor/pom.xml spring-boot:run
```

| Item | Value |
| --- | --- |
| Consumes | `logistics.vehicle.location.v1` |
| Produces | `logistics.vehicle.location.dlq.v1` (invalid records), `logistics.alert.v1` (alerts) |
| Detection | `SPEED_LIMIT`, default 120 km/h; one alert per `NORMAL` to `SPEEDING` transition. `STOPPED_WINDOW_SECONDS`, default 300 s, and `MOVEMENT_THRESHOLD_METERS`, default 50 m; one alert per `MOVING` to `STOPPED` transition |
| State | Latest state of every vehicle - position, speed, heading, last update and status - in a Kafka Streams state store keyed by `vehicleId` |
| Persistence | `vehicles`, `vehicle_latest_state` and `alerts` in PostgreSQL, created by Flyway migrations and written idempotently; telemetry history is deliberately not stored |
| Database | `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` of the local stack, or the standard `SPRING_DATASOURCE_*` overrides |
| Alert contract | `AlertEvent`, version 1, `eventType` of `SPEEDING_DETECTED` or `VEHICLE_STOPPED_DETECTED`, keyed by `vehicleId` |
| Health | `GET http://localhost:8081/actuator/health` |
| Tests | `.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml test` |

Invalid telemetry never stops the stream: the payload is inspected once and either reaches
the two detections or is published to the dead letter topic carrying `originalEvent`,
`error`, `failedAt` and `sourceTopic`. Both detections keep their state per `vehicleId` in a
Kafka Streams state store, so staying above the limit or standing still produces no further
alert, and a restart continues from the restored state instead of repeating an alert that was
already reported.
Every accepted record also overwrites the persisted latest state of the vehicle and stores
the alerts it produces, keyed by their unique `eventId`, so replaying a record cannot
duplicate a row. Telemetry itself is never persisted.

Read [docs/stream-processor.md](docs/stream-processor.md) for the complete configuration
contract, the validation rules, the alert contract and the troubleshooting table.

## Repository Layout

```text
coobi-logistics/
├── services/
│   ├── event-generator/    # Vehicle telemetry simulator
│   ├── stream-processor/   # Kafka Streams processing service
│   └── logistics-api/      # REST API and real-time event streaming
├── frontend/               # Vue 3 + TypeScript dashboard
├── infrastructure/         # Local infrastructure and observability assets
├── docs/                   # Architecture, event contracts and benchmarks
├── .editorconfig
├── .env.example
├── .gitignore
├── compose.yml
├── LICENSE
└── README.md
```

`services/event-generator` holds the MVP-1 implementation and `services/stream-processor`
the MVP-2 and MVP-3 implementation, each with its own Maven wrapper and its service documentation
([docs/event-generator.md](docs/event-generator.md),
[docs/stream-processor.md](docs/stream-processor.md)). The `logistics-api`, `frontend` and
`infrastructure` directories are intentionally empty placeholders at this stage.

## Technology Stack

Java 21, Spring Boot, Apache Kafka, Kafka Streams, PostgreSQL, Vue 3 with TypeScript,
Server-Sent Events, Docker and Docker Compose, Testcontainers, and Prometheus with
Micrometer. Backend services are built with Maven.

## Contributing Workflow

Each issue is implemented on its own short-lived branch cut from `develop`, and is
integrated into `develop` through a pull request once it has been reviewed and verified.
The `main` branch only receives fully tested and verified releases.

## License

Released under the MIT License. See [LICENSE](LICENSE).
