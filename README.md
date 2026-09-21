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

**MVP-0.2 - Local Docker infrastructure in place.**

The repository foundation is in place (MVP-0.1), the environment contract is defined
(MVP-0.3): the complete set of environment variables, their safe development defaults
and their units are documented in `.env.example` and in the Configuration section
below. The local Docker infrastructure is in place (MVP-0.2): `compose.yml` starts a
single-node Kafka broker in KRaft mode and PostgreSQL, and both publish their ports on
the loopback interface only.

No service is implemented yet. The three backend services and the frontend arrive in
the following milestones, which are tracked in the implementation roadmap as milestones
and issues.

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
| `SPEED_LIMIT` | Speed above which a vehicle is reported as speeding | `120` | kilometres per hour (km/h) |
| `STOPPED_WINDOW_SECONDS` | Time a vehicle must stay effectively stationary before it is reported as stopped | `300` | seconds |
| `MOVEMENT_THRESHOLD_METERS` | Total distance below which movement counts as "no movement" over the stopped window | `50` | metres |

Each service reads these variables from its own environment, and a value already
present in that environment wins over any fallback baked into the service. `compose.yml`
interpolates the `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` entries, so an
exported shell variable overrides the value copied into `.env`. Because Compose
interpolates `$` in `.env`, a literal dollar sign inside a value must be doubled:
`POSTGRES_PASSWORD=pa$$word` resolves to the value `pa$word`. The remaining variables
are consumed by the services implemented in later milestones.

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

The service, frontend, infrastructure and documentation directories are intentionally
empty placeholders at this stage.

## Technology Stack

Java, Spring Boot, Apache Kafka, Kafka Streams, PostgreSQL, Vue 3 with TypeScript,
Server-Sent Events, Docker and Docker Compose, Testcontainers, and Prometheus with
Micrometer.

## Contributing Workflow

Each issue is implemented on its own short-lived branch cut from `develop`, and is
integrated into `develop` through a pull request once it has been reviewed and verified.
The `main` branch only receives fully tested and verified releases.

## License

Released under the MIT License. See [LICENSE](LICENSE).
