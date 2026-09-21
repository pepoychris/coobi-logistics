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

**MVP-0.3 - Project configuration defined.**

The repository foundation is in place (MVP-0.1) and the environment contract is now
defined (MVP-0.3): the complete set of environment variables, their safe development
defaults and their units are documented in `.env.example` and in the Configuration
section below.

Local Docker infrastructure (MVP-0.2) is not in place yet, so the variables are not
consumed by anything at this stage. No service is implemented yet either. Kafka,
PostgreSQL, the three backend services and the frontend arrive in the following
milestones, which are tracked in the implementation roadmap as milestones and issues.

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
container needs its own network override, and the in-network hostname and port are
decided by MVP-0.2.

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
present in that environment wins over any fallback baked into the service. The future
Compose definition (MVP-0.2) is expected to interpolate `${VAR}` entries, which is what
will let an exported variable override the value copied into `.env`. That wiring does
not exist yet: nothing consumes these variables at this stage.

`.env` is listed in `.gitignore` and must never be committed. No credential is
versioned in this repository: `POSTGRES_PASSWORD` is documented with an empty value, and
you assign your own local password in `.env` before starting any stack. The MVP-0.2
infrastructure must fail when that variable is missing instead of falling back to a
built-in default.

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
