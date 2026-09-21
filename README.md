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

**MVP-5 - Logistics REST API, MVP-6 - real-time browser streaming, MVP-7 - Vue real-time
dashboard, MVP-8 - observability, MVP-9 - testing and reliability, and MVP-10 - single-command
local deployment.**

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

The REST API is implemented (MVP-5): `services/logistics-api` is a Spring Boot service that
answers the operator views - the fleet, the alerts and the live statistics - over
`/api/v1`, reading the state the processor derived and reporting a value whose source cannot
be read as absent instead of inventing it.
See [docs/logistics-api.md](docs/logistics-api.md).

The browser streams are implemented (MVP-6): the same service answers
`GET /api/v1/stream/events` and `GET /api/v1/stream/statistics` with Server-Sent Events, so a
dashboard draws the events of the pipeline and the live statistics without polling. The events
a browser may see are the ones the processor already stored, sampled into a bounded tick
instead of forwarded from Kafka, and a stream nobody watches costs nothing: its ticker exists
only while a browser is connected.
See [docs/logistics-api.md](docs/logistics-api.md).

The dashboard is implemented (MVP-7): `frontend/` is a Vue 3 application with TypeScript, built
by Vite, that reads that API and its two streams. It draws the five live KPIs of the pipeline,
the fleet on a Leaflet map over OpenStreetMap tiles - one marker per vehicle, updated in place -
and the events the processor stores as they arrive, in a feed bounded to a fixed number of rows.
Every value it shows comes from the backend, a statistic the API reports as absent is drawn as a
gap, and a stream that is not live says so instead of showing stale numbers as if they were
current.
See [docs/frontend.md](docs/frontend.md).

Observability is implemented (MVP-8): the two services that touch Kafka publish Micrometer
metrics in the Prometheus exposition format on `/actuator/prometheus` - the counter of every
event consumed, processed, rejected and turned into an alert, a timer of the processing steps
themselves, and the Kafka client and Kafka Streams metrics of their clients, including the
consumer lag and the processing rate - and `compose.yml` runs the Prometheus that scrapes
both of them. A counter that stays flat means no record reached that stage, rather than a
number that only looks live.
See [docs/observability.md](docs/observability.md).

Testing and reliability are implemented (MVP-9): every minimum of the roadmap has a
deterministic unit test that needs neither a broker nor a database, and what only fails against
real infrastructure is verified against it - a Kafka broker for telemetry, alerts and the dead
letter topic, the PostgreSQL of the stack for the migrations, the persisted state and the
idempotency of a duplicate event id, and one smoke test that generates a scenario with the
simulator of the generator and reads the processed result back through this API, over HTTP.
The container tests are skipped with the reason Testcontainers reports on a machine without
Docker, and a default `mvn test` neither compiles nor needs them.
See [docs/testing-reliability.md](docs/testing-reliability.md).

The local deployment is complete (MVP-10): `docker compose up --build` starts the whole stack -
Kafka, PostgreSQL, the three services, the dashboard and Prometheus - from images this
repository builds, so a machine with nothing installed but Docker can evaluate the project
without installing Java, Maven, Node, Kafka or PostgreSQL. Each application image is a
multi-stage build that runs as an unprivileged user and answers a health check on its own
Actuator endpoint or on the page it serves, every dependency between the services is a
condition on one of those health checks rather than on a startup order, and Prometheus scrapes
the two services under their names on the Compose network.
See [docs/deployment.md](docs/deployment.md).

The performance benchmark is implemented (MVP-11): `infrastructure/benchmarks/benchmark.py`
runs a scenario - a fleet, a target rate and a duration - against that stack, samples the
counters, the timers, the consumer lag and the resources of every container while it runs, and
writes the raw samples, a summary and a report per scenario. The four scenarios of the
roadmap are one command (`--matrix`), the dashboard is left out of the measurement, and a
quantity whose source the stack does not expose is reported as `unavailable` rather than
estimated. The measured results, the hardware they were measured on and the bottlenecks they
expose are in [docs/benchmarks.md](docs/benchmarks.md).

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
| `LOAD_TEST_DURATION` | How long the event generator publishes in `LOAD_TEST` mode before it stops on its own; `0` publishes until the service is stopped | `0` | duration (`5m`, `90s`) or seconds |
| `GENERATOR_RANDOM_SEED` | Seed of the deterministic trajectory generator | `20260101` | long |
| `SPEED_LIMIT` | Speed above which a vehicle is reported as speeding | `120` | kilometres per hour (km/h) |
| `STOPPED_WINDOW_SECONDS` | Time a vehicle must stay effectively stationary before it is reported as stopped | `300` | seconds |
| `MOVEMENT_THRESHOLD_METERS` | Total distance below which movement counts as "no movement" over the stopped window | `50` | metres |
| `STREAM_PROCESSOR_METRICS_URL` | Actuator metrics endpoint of the stream processor, read by `GET /api/v1/statistics` | `http://localhost:8081/actuator/metrics` | URL |
| `COOBI_STREAM_EVENTS_POLL_INTERVAL` | How often the event stream reads the read model for events new since the previous tick | `1s` | duration (`250ms`, `2s`) |
| `COOBI_STREAM_EVENTS_MAX_ALERTS_PER_POLL` | Most alerts one tick of the event stream may carry | `20` | alerts (count) |
| `COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL` | Most vehicle states one tick of the event stream may carry | `30` | vehicle states (count) |
| `COOBI_STREAM_STATISTICS_INTERVAL` | How often the statistics stream reads and sends the live statistics | `1s` | duration (`250ms`, `2s`) |
| `COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS` | Browsers one stream serves at a time; the next connection is answered `503` | `32` | connections (count) |
| `API_PROXY_TARGET` | Origin the dashboard container proxies `/api` to | `http://logistics-api:8082` | URL |

Each service reads these variables from its own environment, and a value already
present in that environment wins over any fallback baked into the service. `compose.yml`
interpolates the `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` entries, so an
exported shell variable overrides the value copied into `.env`. Because Compose
interpolates `$` in `.env`, a literal dollar sign inside a value must be doubled:
`POSTGRES_PASSWORD=pa$$word` resolves to the value `pa$word`. The remaining variables
are consumed by the services: `KAFKA_BOOTSTRAP_SERVERS`, the vehicle simulator
variables and `GENERATOR_RANDOM_SEED` by the event generator (MVP-1), and `SPEED_LIMIT`,
`STOPPED_WINDOW_SECONDS` and `MOVEMENT_THRESHOLD_METERS` by the stream processor
(MVP-2 and MVP-3), and `STREAM_PROCESSOR_METRICS_URL` and the `COOBI_STREAM_*` variables by
the logistics-api (MVP-5 and MVP-6).

`API_PROXY_TARGET` is read by the dashboard container (MVP-10), which proxies `/api` to that
origin so the browser keeps talking to the origin that served the page. The scrape targets of
the Prometheus container are not variables: they are the service names of the stack, written out
in `infrastructure/prometheus/prometheus.yml`, because Prometheus expands `${VAR}` references of
its configuration file in `external_labels` and nowhere else.
[docs/observability.md](docs/observability.md) documents the metrics themselves and
[docs/deployment.md](docs/deployment.md) the stack that produces them.

The dashboard of MVP-7 is not a service with an environment of its own: Vite reads
`VITE_API_BASE_URL` and `VITE_API_PROXY_TARGET` from `frontend/.env` at build time, and both
are documented in [frontend/.env.example](frontend/.env.example). Root `.env.example` names
them and points there, so one place remains the reference for the names of the contract.

`.env` is listed in `.gitignore` and must never be committed. No credential is
versioned in this repository: `POSTGRES_PASSWORD` is documented with an empty value, and
you assign your own local password in `.env` before starting any stack. Compose resolves
that variable before it creates any container, so a missing or empty value aborts the
command instead of starting the stack with no password.

## Local Infrastructure

`compose.yml` at the repository root defines the local development stack:

```text
kafka             apache/kafka:4.3.1                 single-node KRaft broker, no ZooKeeper
postgres          postgres:18.6                      PostgreSQL
event-generator   built from services/event-generator telemetry simulator (MVP-1)
stream-processor  built from services/stream-processor processing and persistence (MVP-2..4)
logistics-api     built from services/logistics-api   REST API and streams (MVP-5 and MVP-6)
frontend          built from frontend                 Vue dashboard (MVP-7)
prometheus        prom/prometheus:v3.14.0-busybox    scrapes the two services (MVP-8)
```

All seven services publish their ports on the loopback interface only, so the stack is never
reachable from another machine (Global Rule 8):

| Service | URL |
| --- | --- |
| Dashboard | <http://localhost:5173> |
| Logistics API | <http://localhost:8082/api/v1/vehicles> |
| Event generator | <http://localhost:8080/actuator/health> |
| Stream processor | <http://localhost:8081/actuator/health> |
| Prometheus | <http://localhost:9090> |
| Kafka | `localhost:9092` |
| PostgreSQL | `localhost:5432` |

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

`docker compose up --build -d` is the canonical local startup command (Global Rule 18). The
`--build` flag builds the four application images from their Dockerfiles, so the command is the
whole setup: Docker is the only thing that has to be installed, and nothing has to be run from
the host. Docker with the Compose plugin `v2.24` or newer is the documented requirement.

`docker compose ps` reports the seven services as `running` and then `healthy`. A service does
not start before the health checks of what it needs have passed - the generator waits for the
broker, the processor for the broker and the database, the API for the database and the
processor, and the dashboard for the API - and a container that is restarted is restarted by
its own policy, so a restart of Kafka or PostgreSQL does not require rebuilding anything.
Compose reads
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

### Prometheus

Prometheus is reachable from the host at `http://localhost:9090` (MVP-8). It scrapes the
Actuator endpoints of the event generator and the stream processor every 15 seconds, using
the configuration in `infrastructure/prometheus/prometheus.yml`, and stores what it reads in
its own volume for 15 days. The two services run in this stack, so the targets are their
service names on the Compose network, `event-generator:8080` and `stream-processor:8081`,
written out in that file: Prometheus expands `${VAR}` references of its configuration in
`external_labels` and nowhere else, so a target holding one would be scraped as its own name.
To scrape a service started from the host instead, change the one target to
`host.docker.internal:8080` or `:8081`.

A target is `down` until the service it describes is running, which is why the Prometheus of
a stack that has only started its infrastructure reports both of them as down. The metric
catalog, the queries worth running against a benchmark and the troubleshooting table live in
[docs/observability.md](docs/observability.md).

## Testing

Every service has a unit suite that needs nothing but the JVM - no broker, no database - which
is why it is the suite to run while working. The tests that only fail against real
infrastructure are integration tests: they start the images of the local stack in containers
and they are the only tests that need Docker.

| Item | Value |
| --- | --- |
| Unit tests, one service | `mvn test -f services/<service>/pom.xml` |
| Unit tests, every service | `mvn test` |
| Integration tests | `mvn -Dintegration-tests test` |
| Deployment contract | `pwsh -File infrastructure/scripts/verify-deployment.ps1` - add `-Stack` to build the images, start the whole stack and probe it over HTTP |
| Benchmark harness | `python -m unittest discover -s infrastructure/benchmarks/tests` - the scenarios, the rates, the percentiles and the report of MVP-11 |
| Contract, layers and commands | [docs/testing-reliability.md](docs/testing-reliability.md) |
| Stack, images and health dependencies | [docs/deployment.md](docs/deployment.md) |
| Performance and its bottlenecks | [docs/benchmarks.md](docs/benchmarks.md) |

The integration tests start `apache/kafka:4.3.1` and `postgres:18.6`, the two images
`compose.yml` runs, and the smoke test starts the stream processor next to the API so that a
generated telemetry scenario can be read back through the public API. The command runs from
the root of the repository because of that: the aggregator `pom.xml` is what lets Maven resolve
the processor and the generator from their build output instead of from their executable jars,
and it stops at the `test` phase because `package` is what produces those jars.
A machine without a Docker daemon skips the container tests with the reason Testcontainers
reports, and the default `mvn test` neither compiles them nor resolves their dependencies.

## Performance Benchmark

`infrastructure/benchmarks/benchmark.py` measures the Dockerized stack of this repository and
writes what it measured under `infrastructure/benchmarks/results/`. It needs Python 3.11 or
newer and the Docker CLI with the Compose plugin, and it starts the stack itself: one command
runs one scenario, and `--matrix` runs the four scenarios of the roadmap.

```bash
# one scenario: vehicles / target events per second / window
python infrastructure/benchmarks/benchmark.py --scenario 5000/10000/5m

# the four scenarios of MVP-11.2, five minutes each
python infrastructure/benchmarks/benchmark.py --matrix
```

| Item | Value |
| --- | --- |
| One scenario | `--scenario <vehicles>/<events per second>/<duration>`, for example `10000/25000/5m` |
| The required matrix | `--matrix`: 1,000/1,000, 5,000/10,000, 10,000/25,000 and 20,000/50,000, five minutes each |
| Plan without measuring | `--dry-run`, or `--list` for the scenarios alone |
| Sampling interval | `--sample-interval 10s` by default, from the Prometheus counters and from `docker stats` |
| Dashboard | Left out of the measurement; `--frontend` includes it |
| Local volumes | Kept; `--reset-volumes` deletes them so a scenario starts from an empty Kafka |
| Harness tests | `python -m unittest discover -s infrastructure/benchmarks/tests` |
| Results | `infrastructure/benchmarks/results/<run>/`: `raw.jsonl`, `summary.json`, `report.md` per scenario, plus `matrix.md` |
| Report | [docs/benchmarks.md](docs/benchmarks.md) |

The harness applies the overlay `infrastructure/benchmarks/compose.benchmark.yml` on top of
`compose.yml`. The overlay is the only thing a benchmark changes about the stack: it drives the
generator in `LOAD_TEST` mode at the fleet and the rate of the scenario, bounds the run with
`LOAD_TEST_DURATION` so a harness that dies leaves no load behind, asks the stream processor for
its `benchmark` Spring profile (the latency histogram the report needs, and nothing else), and
puts the dashboard behind a profile so it is not started at all.

Every number of a report comes from a sample the harness captured: a quantity whose source the
stack does not expose is written as `unavailable`, a scenario that did not produce a value is
written as `not run`, and neither is ever a zero.

## Event Generator

`services/event-generator` simulates the fleet and produces the telemetry that the rest of
the pipeline consumes. It ships a Maven wrapper, so a local Maven installation is not
required, and it needs only a running Kafka broker. It also runs as a container of the stack
(MVP-10), which needs neither a JDK nor Maven: `docker compose up --build`. What follows is the
way to run it from the host, which is what a contributor working on the service does.

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
| Prometheus | `GET http://localhost:8080/actuator/prometheus`, scraped by the Prometheus of the stack (MVP-8) |
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
Kafka broker. It also runs as a container of the stack (MVP-10), which is the way to start it
without a JDK:

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
| Metrics | `GET http://localhost:8081/actuator/prometheus`: the events received, processed and rejected, the alerts per detection, the processing time and the Kafka client metrics (MVP-8) |
| Tests | `.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml test` |
| Integration tests | `mvn -Dintegration-tests verify -f services/stream-processor/pom.xml` - Kafka and PostgreSQL in containers: telemetry to alert, the dead letter topic, the migrations, the persistence and the idempotency (MVP-9.2 and MVP-9.3) |

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

## Logistics REST API

`services/logistics-api` is the read side of the stack. It answers the operator views from
the state the processor has already persisted, and it writes nothing: it maps the tables of
MVP-4, it owns no migration and its connection pool is read-only. It also serves the two
browser streams of MVP-6, which are views of that same state rather than a second copy of it.
It ships a Maven wrapper and needs only the PostgreSQL of the local stack, or nothing at all
when it runs as a container of the stack (MVP-10):

```powershell
docker compose up -d
# the schema belongs to the processor migrations (MVP-4): run it once, or apply them yourself
.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml spring-boot:run
.\services\logistics-api\mvnw.cmd -f services/logistics-api/pom.xml spring-boot:run
```

```bash
docker compose up -d
./services/stream-processor/mvnw -f services/stream-processor/pom.xml spring-boot:run
./services/logistics-api/mvnw -f services/logistics-api/pom.xml spring-boot:run
```

| Item | Value |
| --- | --- |
| Base path | `/api/v1` |
| Endpoints | `GET /api/v1/vehicles`, `GET /api/v1/vehicles/{vehicleId}`, `GET /api/v1/alerts`, `GET /api/v1/alerts/{id}`, `GET /api/v1/statistics` |
| Streams | `GET /api/v1/stream/events` and `GET /api/v1/stream/statistics`, Server-Sent Events that stay open: the `alert` and `vehicle` events that happen while a browser is connected, and the live statistics re-sent every interval |
| Vehicles | One page of the fleet, newest telemetry first, optionally filtered by `status`; an unknown vehicle is a `404` |
| Alerts | One page of alerts, newest first, filtered independently by `vehicleId`, `type` and `severity`; an unknown alert is a `404` |
| Paging | `page` from `0`, `size` between `1` and `100`; the order of a page is fixed by the endpoint so paging is stable |
| Statistics | `processedEvents` and `eventsPerSecond` from the counter the stream processor publishes over Actuator (MVP-8), `activeVehicles` and `alertsGenerated` counted in the database, `uptimeSeconds` of this instance - a source that cannot be read is reported as `null` |
| Bounded streams | A tick carries at most `COOBI_STREAM_EVENTS_MAX_ALERTS_PER_POLL` alerts and `COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL` vehicle states, a burst is sampled rather than queued, a browser that stops reading loses its oldest frames, and a stream with no browser connected has no ticker at all |
| Stream configuration | `COOBI_STREAM_EVENTS_POLL_INTERVAL`, `COOBI_STREAM_EVENTS_MAX_ALERTS_PER_POLL`, `COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL`, `COOBI_STREAM_STATISTICS_INTERVAL` (`1s` by default) and `COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS`; a stream at capacity answers `503` |
| Persistence | Reads `vehicles`, `vehicle_latest_state` and `alerts`; creates nothing and writes nothing |
| Database | `POSTGRES_DB`, `POSTGRES_USER` and `POSTGRES_PASSWORD` of the local stack, or the standard `SPRING_DATASOURCE_*` overrides |
| Errors | RFC 9457 problem details: `400` for a parameter the client can correct, `404` for an unknown resource, `500` without internals |
| Health | `GET http://localhost:8082/actuator/health` |
| Tests | `.\services\logistics-api\mvnw.cmd -f services/logistics-api/pom.xml test` |
| Integration tests | `mvn -Dintegration-tests verify` from the root of the repository - the full pipeline smoke test: generated telemetry, Kafka, the processor, PostgreSQL and these endpoints (MVP-9.4) |

The field names and units of a vehicle are the ones of the version-1 location contract the
services share, and the metadata of an alert is the `AlertData` of the alert contract,
embedded as JSON. Responses are DTOs, so the storage mapping can change without changing the
contract, and `SchemaContractTest` pins those DTOs to the schema: it reads the migrations of
the stream processor and fails when the columns of the entities and the columns of the
migrations drift apart.

Read [docs/logistics-api.md](docs/logistics-api.md) for the complete configuration contract,
the response of every endpoint, the source of every statistic and the troubleshooting table.

## Vue Dashboard

`frontend/` is the public-facing half of the project: the screen a reader opens to see the
pipeline working. In the stack it is a container (MVP-10) that serves the built bundle on
<http://localhost:5173> and proxies `/api` to the API itself, which is the way to open it
without installing Node. Working on it needs Node.js 20.19 or newer and the API of the local
stack:

```powershell
cd frontend
npm install
npm run dev
```

```bash
cd frontend
npm install
npm run dev
```

The dev server listens on <http://localhost:5173> and proxies `/api` to `http://localhost:8082`,
so the page reaches the API of its own origin - which is what makes the streams work in a
browser without enabling CORS on a service that deliberately does not answer other origins.

| Item | Value |
| --- | --- |
| Stack | Vue 3 with TypeScript, Vite, Leaflet over OpenStreetMap tiles; no UI framework |
| Reads | `GET /api/v1/statistics`, `GET /api/v1/vehicles`, `GET /api/v1/stream/events` and `GET /api/v1/stream/statistics`; it writes nothing |
| Configuration | `VITE_API_BASE_URL` (empty means the origin that served the dashboard) and `VITE_API_PROXY_TARGET` (`http://localhost:8082`), both documented in [frontend/.env.example](frontend/.env.example) |
| KPIs | `Events processed`, `Events per second`, `Active vehicles`, `Alerts generated` and `System status`, every one of them read from the API; a value reported as `null` is drawn as a gap and never as a zero |
| Map | One marker per `vehicleId`, created for a vehicle that is new and moved afterwards, so the map is laid out once; a click shows `vehicleId`, `speed`, `status` and `lastUpdate` |
| Feed | The `alert` and `vehicle` events of the stream, oldest first, alerts set apart from telemetry, at most 100 rows in the browser with the oldest evicted first |
| States | Connecting, live, reconnecting and disconnected, each of them visible in the header and in the KPI cards |
| Build | `npm run build` type-checks with `vue-tsc` and writes `dist/` |
| Tests | `npm test` - deterministic, offline, with `fetch` and `EventSource` replaced by stand-ins |

Read [docs/frontend.md](docs/frontend.md) for the environment contract, the states of a stream,
the bounds the browser keeps and the troubleshooting table.

## Repository Layout

```text
coobi-logistics/
├── services/
│   ├── event-generator/    # Vehicle telemetry simulator + Dockerfile
│   ├── stream-processor/   # Kafka Streams processing service + Dockerfile
│   └── logistics-api/      # REST API and real-time event streaming + Dockerfile
├── frontend/               # Vue 3 + TypeScript dashboard, its Dockerfile and nginx template
├── infrastructure/         # Prometheus configuration, the deployment check and the benchmark harness
├── docs/                   # Architecture, event contracts and benchmarks
├── .editorconfig
├── .env.example
├── .gitignore
├── compose.yml
├── LICENSE
└── README.md
```

`services/event-generator` holds the MVP-1 implementation, `services/stream-processor` the
MVP-2 to MVP-4 implementation and `services/logistics-api` the MVP-5 and MVP-6 implementation,
each with its own Maven wrapper and its service documentation
([docs/event-generator.md](docs/event-generator.md),
[docs/stream-processor.md](docs/stream-processor.md),
[docs/logistics-api.md](docs/logistics-api.md)). `frontend` holds the MVP-7 dashboard, built by
npm rather than Maven and documented in [docs/frontend.md](docs/frontend.md).
`infrastructure` holds the assets of the local stack that are not Compose definitions, today
the scrape configuration of Prometheus (MVP-8), documented in
[docs/observability.md](docs/observability.md).

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
