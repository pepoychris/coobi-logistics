# Stream processor (MVP-2 and MVP-3)

The `stream-processor` service consumes the telemetry published by the event generator,
validates it and turns relevant conditions into alerts:

```text
event-generator -> Kafka (logistics.vehicle.location.v1)
                                |
                                v
                        stream-processor (Kafka Streams)
                          |                    |
                          v                    v
        logistics.vehicle.location.dlq.v1   logistics.alert.v1
                (invalid events)        (speeding and stopped alerts)
```

## Quick start

Requirements: JDK 21 and a local Kafka broker. The repository ships a Maven wrapper, so no
local Maven installation is needed.

```powershell
# 1. Infrastructure. Copy the template once and set a local POSTGRES_PASSWORD:
#    Compose interpolates it for the postgres service and aborts when it is empty.
Copy-Item .env.example .env
docker compose up -d

# 2. Run the generator so there is telemetry to process.
.\services\event-generator\mvnw.cmd -f services/event-generator/pom.xml spring-boot:run

# 3. Run the processor. Kafka is reached on localhost:9092 by default.
.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml spring-boot:run
```

```bash
cp .env.example .env
docker compose up -d
./services/event-generator/mvnw -f services/event-generator/pom.xml spring-boot:run
./services/stream-processor/mvnw -f services/stream-processor/pom.xml spring-boot:run
```

The service listens on `http://localhost:8081`, so it runs next to the generator on 8080.

## Configuration

Configuration is read from the process environment. The variables below are the documented
contract and are kept in sync with `.env.example` and README.md.

| Variable | Purpose | Default | Units |
| --- | --- | --- | --- |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap servers, as reachable from the host process | `localhost:9092` | comma-separated `host:port` list |
| `SPEED_LIMIT` | Speed strictly above which a vehicle is reported as speeding | `120` | kilometres per hour (km/h) |
| `STOPPED_WINDOW_SECONDS` | Time a vehicle must stay effectively stationary before it is reported as stopped | `300` | seconds |
| `MOVEMENT_THRESHOLD_METERS` | Total distance below which movement counts as "no movement" over the stopped window | `50` | metres |
| `COOBI_KAFKA_INITIALIZATION_ENABLED` | Provision the Kafka topics on startup; `false` runs the service without a broker | `true` | boolean |
| `POSTGRES_DB` | Database name, shared with the Compose stack | `logistics` | database name |
| `POSTGRES_USER` | Database role of the service | `logistics` | role name |
| `POSTGRES_PASSWORD` | Password of that role, the same value the Compose stack uses | (none) | string |
| `SPRING_DATASOURCE_URL` | JDBC URL, for a database that is not the local stack | `jdbc:postgresql://localhost:5432/${POSTGRES_DB}` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Overrides the database role | `POSTGRES_USER` | role name |
| `SPRING_DATASOURCE_PASSWORD` | Overrides the database password | `POSTGRES_PASSWORD` | string |

The `POSTGRES_*` variables are the ones `.env` already holds for the Compose service, so one
file configures the stack and this service. The `SPRING_DATASOURCE_*` names are the standard
Spring overrides and win over them.

Every other setting lives in
[`application.yml`](../services/stream-processor/src/main/resources/application.yml) and can
still be overridden with Spring's relaxed binding, for example
`COOBI_KAFKA_TOPICS_ALERT=logistics.alert.test.v1`, `COOBI_KAFKA_TOPICS_PARTITIONS=12` or
`SPRING_KAFKA_STREAMS_AUTO_STARTUP=false`.

An invalid value aborts startup instead of degrading silently: the speed limit and the
movement threshold must be finite numbers greater than zero, the stopped window must be at
least one second, and the topic names, the partition count and the replication factor are
validated while the application context is built.

## Topics

| Topic | Direction | Purpose |
| --- | --- | --- |
| `logistics.vehicle.location.v1` | consumed | Versioned telemetry from the generator |
| `logistics.vehicle.location.dlq.v1` | produced | Records that could not be accepted |
| `logistics.alert.v1` | produced | Speeding and stopped-vehicle alerts |

All three are provisioned on startup with six partitions and a replication factor of one,
and provisioning is idempotent: the topics the generator already created are reused, the
missing ones are created, and a topic created by a concurrent starter is accepted. A
broker that is unreachable is retried a bounded number of times and then fails startup, so
the processor never runs without a destination for its alerts.

## Validation and the dead letter topic

Every record is inspected once, and the outcome routes it to one of the two branches.
Nothing is thrown, so a malformed record can never stop the records behind it:

| Rejection | Example error |
| --- | --- |
| Blank payload | `payload must not be blank` |
| Payload that is not JSON | `malformed JSON payload: Unexpected character ...` |
| Payload that is not a JSON object | `payload must be a JSON object` |
| Unsupported schema version or event type | `unsupported event version: 2 (expected 1)` |
| Values outside the contract | `invalid event: data.latitude latitude must be less than or equal to 90` |
| Blank or mismatched Kafka key | `record key must match the event vehicleId: key=..., vehicleId=...` |

`vehicleId` is validated both in the payload and as the Kafka key, because every
vehicle-specific event is keyed by it (Global Rule 15).

Rejected records are published to the dead letter topic with the payload exactly as it was
consumed:

```json
{
  "originalEvent": "{\"eventId\":\"...\",\"eventType\":\"VEHICLE_LOCATION_UPDATED\",...}",
  "error": "invalid event: data.latitude latitude must be less than or equal to 90",
  "failedAt": "2026-09-21T09:15:00.500Z",
  "sourceTopic": "logistics.vehicle.location.v1"
}
```

`originalEvent` is the payload text rather than a nested document, so a record that is not
valid JSON at all still round-trips unchanged.

## Speeding detection

The detection is a two-state machine per vehicle, keyed by `vehicleId` and stored in a
Kafka Streams key-value store with a changelog:

```text
NORMAL -- speed > SPEED_LIMIT --> SPEEDING   (one alert)
SPEEDING -- speed <= SPEED_LIMIT --> NORMAL  (no alert)
```

Only the transition into `SPEEDING` produces an alert, so a vehicle that stays above the
limit produces exactly one alert, and returning below the limit and exceeding it again
produces a second one. The state lives in the store instead of the processor instance, so
a restart continues from the committed state rather than re-emitting an alert for a
crossing that was already reported.

## Vehicle state (MVP-3.1)

Every accepted record is also written to the `vehicle-state-store`, another keyed Kafka
Streams state store with a changelog, keyed by `vehicleId`. It holds the latest state of the
vehicle, which is what the stopped-vehicle detection below decides on, and what a later
milestone can read to draw the fleet without replaying the topic:

```json
{
  "latitude": 39.4699,
  "longitude": -0.3763,
  "speed": 0.0,
  "heading": 214.5,
  "lastUpdate": "2026-09-21T09:15:00Z",
  "status": "MOVING",
  "windowStartedAt": "2026-09-21T09:15:00Z",
  "accumulatedMeters": 0.0
}
```

`lastUpdate` is the timestamp of the event itself, not the moment the processor happened to
see it. The two last fields are the bookkeeping of the stopped-vehicle window: the instant
the current window started and the distance the vehicle has covered since then.

The records of one `vehicleId` are processed in the order of their partition and the store is
keyed by that same `vehicleId`, so what the store holds is the latest event of the vehicle
and never a mixture of an older and a newer one. The store is persistent and logged by
default, so a restart restores it from its changelog instead of reopening every window at
zero.

## Stopped-vehicle detection (MVP-3.2)

A vehicle is reported as stopped once it has stayed effectively stationary for
`STOPPED_WINDOW_SECONDS` (300 by default). "Effectively stationary" is judged on the ground
the vehicle actually covered rather than on the speed it reported: distances between
consecutive positions are measured with the Haversine great-circle formula - telemetry
carries geographic coordinates, and near the latitude of the simulated fleet a degree of
longitude covers only about three quarters of a degree of latitude - and those steps are
added up over the window.

```text
MOVING  -- accumulated >= MOVEMENT_THRESHOLD_METERS ------------> MOVING, window restarts
MOVING  -- accumulated <  threshold and elapsed >= window ------> STOPPED (one alert)
MOVING  -- accumulated <  threshold and elapsed <  window ------> MOVING, window continues
STOPPED -- accumulated >= MOVEMENT_THRESHOLD_METERS ------------> MOVING, window restarts
STOPPED -- accumulated <  threshold ----------------------------> STOPPED (no alert)
```

Only the transition into `STOPPED` produces an alert, so a vehicle that stays parked
produces exactly one, and driving away resets its status: the next stationary window
produces the following alert. The elapsed time is read from the telemetry timestamps instead
of from the clock, so a restart cannot shift a window that is already in progress and the
detection is reproducible in a test that covers five minutes in milliseconds.

## Alert contract

```json
{
  "eventId": "0f3c1a5e-2b1f-4a1f-9d4e-8c2f5b6a7d10",
  "eventType": "SPEEDING_DETECTED",
  "version": 1,
  "vehicleId": "TRUCK-00182",
  "severity": "WARNING",
  "timestamp": "2026-09-21T09:15:00.123Z",
  "data": { "speed": 137.2, "threshold": 120.0 }
}
```

Alerts are immutable records with a unique `eventId`, they are versioned (`version` is 1),
and they are keyed by `vehicleId`. The `eventType` and the severity are derived from the
`AlertType`, so the three fields cannot disagree: `SPEEDING` serializes as
`SPEEDING_DETECTED` and `VEHICLE_STOPPED` as `VEHICLE_STOPPED_DETECTED`. Both types are
produced: `SPEEDING` by the speed-limit detection and `VEHICLE_STOPPED` by the
stopped-vehicle detection. The `timestamp` is the timestamp of the telemetry event that
crossed the threshold, and `threshold` is the configured threshold of that detection,
written as a JSON number. The meaning of the two numbers of `data` follows the alert type:

| Alert type | `data.speed` | `data.threshold` |
| --- | --- | --- |
| `SPEEDING_DETECTED` | observed speed | `SPEED_LIMIT`, in km/h |
| `VEHICLE_STOPPED_DETECTED` | speed of the last event, near zero | `MOVEMENT_THRESHOLD_METERS`, in metres |

The stopped alert therefore looks like this:

```json
{
  "eventId": "b0d5a3f1-8e2c-4f7a-9d1e-2c6b7a4f8e91",
  "eventType": "VEHICLE_STOPPED_DETECTED",
  "version": 1,
  "vehicleId": "TRUCK-00042",
  "severity": "WARNING",
  "timestamp": "2026-09-21T09:20:00Z",
  "data": { "speed": 0.0, "threshold": 50.0 }
}
```

## Persistence (MVP-4)

PostgreSQL holds the state this service *derives*, never the telemetry it consumes. There is
no table of past positions: telemetry history is a milestone non-goal, and the Kafka topic
remains the only record of what was published.

| Table | Rows | Written by |
| --- | --- | --- |
| `vehicles` | one per vehicle ever seen; `vehicle_id` unique, `created_at` kept | the state branch, when it sees a vehicle for the first time |
| `vehicle_latest_state` | exactly one per vehicle, overwritten in place | the state branch, once per accepted record |
| `alerts` | one per accepted alert; `event_id` unique | both detections, when they accept an alert |

`vehicle_latest_state` carries position, speed, heading, status and `last_update` - the
timestamp of the telemetry event, not of the write - plus `updated_at`, so a stale row is
distinguishable from a vehicle that stopped reporting. It references `vehicles` with a
cascading foreign key. `alerts` keeps `event_id`, `vehicle_id`, `type`, `severity`,
`occurred_at`, `created_at` and the `data` document of the alert as `JSONB`; `vehicle_id` is
deliberately not a foreign key, because the two detections consume the same telemetry record
independently, so an alert can reach the table before the state branch has written the
vehicle row.

### Idempotency

Kafka Streams delivers at least once, so every write has to be harmless when it happens
twice:

- the vehicle upsert inserts the vehicle row only when it is missing, then updates the single
  state row and inserts it when the update matched nothing;
- the alert insert is guarded by the unique `event_id`. A second delivery of the same alert
  hits the constraint, which the repository treats as "already stored" rather than as an
  error; a replayed Kafka record carries the `eventId` it had the first time, so it cannot
  become a second row;
- a write that loses a race with a concurrent writer of the same vehicle is retried once,
  which then takes the update branch. Idempotency does not depend on writers being
  serialized: the unique constraints are the backstop.

### Integration point and trade-off

The persistence calls live inside the two detection processors, so the derived state is
written by the thread that derived it: the state branch writes the vehicle and its latest
state, and both branches store the alerts they accept before publishing them.

The write is synchronous JDBC on the stream thread. That keeps the sequence "derive, store,
publish" reproducible and avoids a second delivery path, at the cost of tying the throughput
of the stream to the database: a slow database slows the stream, and a failing write fails
the record instead of being swallowed. Kafka Streams then retries that record against the
same restored state, which is safe because every write is idempotent. Moving the writes
behind a bounded queue is a later optimisation; nothing in the topology depends on the
current placement.

### Schema

The schema belongs to Flyway. The migrations under
[`db/migration`](../services/stream-processor/src/main/resources/db/migration) are its only
definition, they run before the context is considered started, and the service declares no
JPA entity, so no ORM can create or update a table. `spring.jpa.hibernate.ddl-auto` is set to
`none` as a guard for the day an entity is added.

## Observability

| Signal | Where |
| --- | --- |
| Health | `GET http://localhost:8081/actuator/health` |
| Info | `GET http://localhost:8081/actuator/info` |
| Speeding detections | `INFO` log line per alert, with the vehicle, the speed and the threshold |
| Stopped detections | `INFO` log line per alert, with the vehicle, the window and the movement threshold |
| Rejections | `WARN` log line per record routed to the dead letter topic |
| Recovery | `DEBUG` log line when a vehicle returns below the limit |
| Persistence | `DEBUG` log line when a duplicate alert is ignored; `WARN` when a concurrent writer forces a retry of a vehicle upsert |

## Running without a broker

Both switches are needed: one keeps the topology from starting, the other skips topic
provisioning. The Kafka health indicator is disabled as well, because it would report the
absent broker instead of the service state.

| Setting | Effect |
| --- | --- |
| `SPRING_KAFKA_STREAMS_AUTO_STARTUP=false` | Builds the topology but never starts it |
| `COOBI_KAFKA_INITIALIZATION_ENABLED=false` | Skips topic provisioning; the relaxed-binding form of `coobi.kafka.initialization.enabled` |
| `MANAGEMENT_HEALTH_KAFKA_ENABLED=false` | `/actuator/health` stays `UP` without Kafka |

```powershell
$env:SPRING_KAFKA_STREAMS_AUTO_STARTUP="false"
$env:COOBI_KAFKA_INITIALIZATION_ENABLED="false"
$env:MANAGEMENT_HEALTH_KAFKA_ENABLED="false"
.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml spring-boot:run
```

## Tests

```powershell
.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml test
```

```bash
./services/stream-processor/mvnw -f services/stream-processor/pom.xml test
```

The suite runs without a broker and without a database server. The topology is driven with `TopologyTestDriver`, which
covers the validation outcomes, the dead letter envelope, the speed-limit transitions, the
contents of the state store and the stopped-vehicle transitions, including the continuity of
valid records after an invalid one. The stopped-vehicle timelines are written with the
timestamps of the telemetry itself, so a five-minute window is covered without a single
wait. The remaining tests cover binding and fail-fast configuration, the documented
environment variables, topic provisioning against a mocked admin client and the serialized
shape, versioning and immutability of every contract. The persistence tests apply the shipped
migrations and run the production SQL against an in-memory database in PostgreSQL mode, which
is what proves the upsert behaviour and the duplicate-`event_id` idempotency without a
server; another group drives the wired topology with a recorder standing in for the database,
to cover which records reach the port and that a rejected one never does.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Startup fails with `kafka topic initialization failed` | No broker on `KAFKA_BOOTSTRAP_SERVERS`. Start `docker compose up -d`, or disable provisioning for offline work. |
| `/actuator/health` reports `DOWN` | The Kafka health indicator cannot reach the broker. Start the broker, or set `MANAGEMENT_HEALTH_KAFKA_ENABLED=false`. |
| No alerts although the generator is running | The simulated fleet peaks at 110 km/h, below the 120 km/h default. Lower `SPEED_LIMIT`, for example `$env:SPEED_LIMIT="90"`. |
| No stopped-vehicle alerts although the generator is running | A stopped alert needs `STOPPED_WINDOW_SECONDS` without movement. If the simulated fleet never stands still that long, lower the window, for example `$env:STOPPED_WINDOW_SECONDS="30"`. |
| Records land in the dead letter topic | Their `error` field names the rejected field. The most common cause is a payload written by a different schema version. |
| `mvnw.cmd` fails under PowerShell 7 | Use an installed Maven as an equivalent fallback: `mvn -f services/stream-processor/pom.xml test`. |
| Startup fails with a Flyway or connection error | PostgreSQL is not reachable with the configured credentials. Start `docker compose up -d`, and check that `POSTGRES_PASSWORD` is set in the shell that runs the service. |
| Startup fails with `Detected failed migration` | The schema was created by hand before the migrations ran. Inspect `flyway_schema_history` and the tables before repairing anything; the migrations are the only definition of the schema. |
| Alerts appear in Kafka but not in `alerts` | The insert failed and the record is being retried, so the table catches up with the topic. Check the health endpoint and the database logs. |
