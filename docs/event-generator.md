# Event generator (MVP-1)

The `event-generator` service simulates a vehicle fleet and publishes versioned telemetry
events to Kafka. It is the first stage of the pipeline:

```text
event-generator  ->  Kafka (logistics.vehicle.location.v1)  ->  stream-processor (MVP-2)
```

One process keeps a fleet state, advances it on a schedule and publishes bounded batches
at a configurable target rate.

## Quick start

Requirements: JDK 21 and a local Kafka broker. The repository ships a Maven wrapper, so no
local Maven installation is needed.

```powershell
# 1. Infrastructure. Copy the template once and set a local POSTGRES_PASSWORD:
#    Compose interpolates it for the postgres service and aborts when it is empty.
Copy-Item .env.example .env
docker compose up -d

# 2. Run the generator. Kafka is reached on localhost:9092 by default.
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
```

```bash
cp .env.example .env
docker compose up -d
./services/event-generator/mvnw -f services/event-generator/pom.xml spring-boot:run
```

The service listens on `http://localhost:8080`. When Maven is already installed,
`mvn -f services/event-generator/pom.xml ...` is equivalent to the wrapper call. A `.env`
file that still has an empty `POSTGRES_PASSWORD` makes `docker compose` abort before it
creates any container, even though the generator does not use PostgreSQL.

## Configuration

Configuration is read from the process environment. The variables below are the documented
contract and are kept in sync with `.env.example` and README.md.

| Variable | Purpose | Default | Units |
| --- | --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap servers, as reachable from the host process | `localhost:9092` | comma-separated `host:port` list |
| `VEHICLE_COUNT` | Simulated vehicles used by `NORMAL` | `1000` | vehicles (count) |
| `TARGET_EVENTS_PER_SECOND` | Publication rate used by `NORMAL` | `1000` | events per second |
| `GENERATOR_MODE` | Operating profile: `NORMAL` or `LOAD_TEST` | `NORMAL` | enum |
| `GENERATOR_PUBLISH_ENABLED` | Publish telemetry, or start the service without producing | `true` | boolean |
| `COOBI_KAFKA_INITIALIZATION_ENABLED` | Provision the Kafka topics on startup; `false` runs the service without a broker | `true` | boolean |
| `LOAD_TEST_VEHICLE_COUNT` | Simulated vehicles used by `LOAD_TEST` | `5000` | vehicles (count) |
| `LOAD_TEST_TARGET_EVENTS_PER_SECOND` | Publication rate used by `LOAD_TEST` | `20000` | events per second |
| `GENERATOR_RANDOM_SEED` | Seed of the deterministic trajectory generator | `20260101` | long |

Every other setting lives in
[`application.yml`](../services/event-generator/src/main/resources/application.yml) and can
still be overridden with Spring's relaxed binding, for example
`COOBI_GENERATOR_TICK_INTERVAL_MILLIS=200` or `COOBI_KAFKA_INITIALIZATION_ENABLED=false`.

An invalid value aborts startup instead of degrading silently: the counts, the target rate,
the tick length and the simulation settings are validated while the application context is
built.

### Modes

`NORMAL` is the demonstration profile: 1,000 vehicles publishing 1,000 events per second.
`LOAD_TEST` uses the `load-test.*` values instead, so a higher load can be driven without
overwriting the demonstration defaults:

```powershell
$env:GENERATOR_MODE="LOAD_TEST"
$env:LOAD_TEST_TARGET_EVENTS_PER_SECOND="50000"
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
```

`GENERATOR_PUBLISH_ENABLED=false` starts the service and its endpoints without producing
events, but it does not remove the broker requirement: the topics are still provisioned
and the Kafka health indicator still checks the broker.

### Running without a broker

Only the second switch removes the Kafka requirement:

| Setting | Effect |
| --- | --- |
| `GENERATOR_PUBLISH_ENABLED=false` | Produces no events; the topics are still provisioned and the health indicator still checks Kafka |
| `COOBI_KAFKA_INITIALIZATION_ENABLED=false` | Skips topic provisioning; it is the relaxed-binding form of `coobi.kafka.initialization.enabled` |

```powershell
$env:GENERATOR_PUBLISH_ENABLED="false"
$env:COOBI_KAFKA_INITIALIZATION_ENABLED="false"
$env:MANAGEMENT_HEALTH_KAFKA_ENABLED="false"   # /actuator/health stays UP without Kafka
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run
```

The shipped defaults keep initialization enabled, so the service fails fast when the
configured broker is unreachable instead of running with no destination. Provisioning and
publishing are ordered by lifecycle phase: the topics are in place before the scheduler
publishes its first event.

## Event contract

`VehicleLocationEvent`, schema version 1, published to `logistics.vehicle.location.v1`
with the `vehicleId` as the Kafka message key, so all events of one vehicle share a
partition and keep their relative order.

```json
{
  "eventId": "6b9f6f5e-6f25-4e8f-8f5f-5c0f0d1a2b3c",
  "eventType": "VEHICLE_LOCATION_UPDATED",
  "version": 1,
  "vehicleId": "TRUCK-00001",
  "timestamp": "2026-09-21T09:15:00.123Z",
  "data": {
    "latitude": 39.4699,
    "longitude": -0.3763,
    "speed": 82.3,
    "heading": 214.5
  }
}
```

| Field | Rule |
| --- | --- |
| `eventId` | non-null, unique per event (UUID) |
| `eventType` | always `VEHICLE_LOCATION_UPDATED` |
| `version` | always `1` |
| `vehicleId` | non-empty, at most 64 characters |
| `timestamp` | non-null, UTC instant, serialized as ISO-8601 with `Z` |
| `data.latitude` | `-90.0 .. 90.0` |
| `data.longitude` | `-180.0 .. 180.0` |
| `data.speed` | `>= 0` |
| `data.heading` | `0 <= heading < 360` |

The event is a Java record: immutable, JSON serializable and safe to share between threads.
The canonical constructor rejects nulls and any schema version other than the current one,
and the range rules above are declared with Jakarta Bean Validation so untrusted input can
be validated at the boundary. A new schema version is a new record version, never a
mutation of version 1.

## Topics

| Topic | Partitions | Replication factor | Purpose |
| --- | --- | --- | --- |
| `logistics.vehicle.location.v1` | 6 | 1 | vehicle location telemetry |
| `logistics.vehicle.location.dlq.v1` | 6 | 1 | dead letter topic for invalid records (consumed from MVP-2) |

The generator provisions both topics on startup, before the first publish:

- the existing topic names are listed first, so a repeated start is idempotent;
- only the missing topics are created, with the configured partition count and replication
  factor;
- a topic with fewer partitions than configured is expanded, and a topic with more is
  reported and left untouched, because Kafka cannot shrink partitions;
- an unreachable broker is retried a bounded number of times (5 attempts, 3 s apart by
  default) and then fails startup.

Six partitions exist so the stream processor of MVP-2 can scale its consumers, and
replication factor 1 matches the single-node broker of `compose.yml`. Inspect the topics
from the host:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --describe --topic logistics.vehicle.location.v1
```

## Simulator behaviour

- **Deterministic identifiers.** Vehicles are `TRUCK-00001` ... `TRUCK-<VEHICLE_COUNT>`,
  generated in order, so the same configuration always produces the same fleet.
- **Deterministic trajectories.** One seeded random generator drives the initial fleet
  distribution and every subsequent change, consumed in round-robin order.
- **Demonstration area.** The fleet starts inside a 30 km disc around `39.4699, -0.3763`,
  the coordinates used by the contract example.
- **Gradual movement.** An update advances the vehicle by the time elapsed since that
  vehicle's previous update, so distance, speed and timestamp stay consistent. Speed and
  heading change by at most the configured per-update delta, and a vehicle that reaches the
  edge of the area is clamped onto the boundary and steered back towards the centre inside
  the same heading budget. Vehicles never jump between unrelated locations.
- **Bounded steps.** An update never simulates more than `max-elapsed-seconds` (10 s by
  default), so a paused process cannot produce a teleport.

## Publishing, rate and shutdown

Publishing is driven by one scheduled task, not one thread per vehicle:

1. every tick (100 ms by default) the planner converts the target rate into a batch size,
   carrying the fraction into the next tick so the average matches the target;
2. a batch never exceeds a bounded multiple of the nominal size, so a slow tick cannot be
   followed by an unbounded burst;
3. each event is serialized once and sent asynchronously with `vehicleId` as the key.

Delivery failures are logged with the event and vehicle identifiers and counted; they are
never propagated, so one rejected record cannot stop the generator. On shutdown the
application cancels the scheduling task and flushes the producer before exiting
(`server.shutdown: graceful`, 20 s shutdown phase).

## Observability

| Signal | Where |
| --- | --- |
| Liveness and state | `GET http://localhost:8080/actuator/health` |
| Counters | `/actuator/metrics/coobi.generator.events`, tagged `result=published` or `result=failed` |
| Prometheus | `GET http://localhost:8080/actuator/prometheus`, scraped by the Prometheus of the local stack (MVP-8) |
| Kafka producer | The `kafka.producer.*` metrics of the client - records per second, errors, requests in flight - are bound by Micrometer and published on the same endpoint |
| Rate logs | `telemetry throughput events-per-second=... published-total=... failed-total=...`, every 30 s by default |
| Topic provisioning | `kafka topics created` / `already present` / `expanded` / `verified` |
| Shutdown summary | `telemetry publisher stopped published-total=... failed-total=...` |

Health includes the Kafka connection through the Spring Boot health indicator, which is why
it can report `DOWN` (HTTP 503) while the broker is unreachable; the admin client timeouts
keep that check bounded to about 15 s. Set `management.health.kafka.enabled=false` to report
the service state alone.

The producer metrics are bound because `KafkaClientConfiguration` applies the producer
customizers of the context to the factory this service declares, which is what Spring Boot
would otherwise have done for a factory of its own. The catalog of the metrics of the whole
pipeline, with the queries to run against a benchmark, is in
[observability.md](observability.md).

Verify events end to end:

```powershell
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic logistics.vehicle.location.v1 --property print.key=true --max-messages 5
```

## Tests

```powershell
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml test
```

The suite runs without Kafka: the admin client is mocked, and every context test disables
topic provisioning and publishing through the `coobi.kafka.initialization.enabled` override.
A dedicated binding test asserts that the documented environment form,
`COOBI_KAFKA_INITIALIZATION_ENABLED=false`, really binds. If `mvnw.cmd` fails under
PowerShell 7, for example because the wrapper download or a proxy fails, an installed Maven
is an equivalent fallback: `mvn -f services/event-generator/pom.xml test`.

| Area | Coverage |
| --- | --- |
| Contract validation | range rules, null and schema-version rejection, boundary values |
| Contract serialization | exact JSON document, ISO-8601 UTC timestamp, round trip, immutability |
| Simulator | deterministic unique identifiers, gradual movement, area containment, step cap, replay for the same seed, 1,000 vehicles |
| Topic provisioning | creation with six partitions and replication factor 1, idempotency, expansion, concurrent creation, fail-fast on an unreachable broker |
| Publishing | `vehicleId` as key, JSON payload, failure counting and logging, counters, producer flush |
| Lifecycle order | provisioning completes before the first publish; closing the context cancels publishing and flushes the producer |
| Rate control | target rate on average, fraction carry-over, bounded batch |
| Configuration | defaults, mode resolution, environment overrides, fail-fast validation |

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Startup fails with `kafka topic initialization failed` | No broker reachable at `KAFKA_BOOTSTRAP_SERVERS`. Start `docker compose up -d kafka`, or run with `COOBI_KAFKA_INITIALIZATION_ENABLED=false`. |
| `/actuator/health` returns `DOWN` | The Kafka health indicator cannot reach the broker. Expected while Kafka is down. |
| `telemetry event publication failed` | The broker rejected a record or could not confirm it. The counters and the topic state separate a broker, ACL or timeout problem. |
| Topics are still missing after startup | Topic creation is retried and then aborts startup; the `kafka topics ...` log lines report the attempt and the reason. |
| `mvnw.cmd` fails under PowerShell 7 | The wrapper downloads a Maven distribution and can hit quoting, proxy or permission problems. Use the installed Maven instead: `mvn -f services/event-generator/pom.xml test`. |

## Out of scope for MVP-1

No stream processing, persistence, frontend or authentication, no schema registry and no
Avro, and no Dockerfile for this service. Those belong to later milestones.
