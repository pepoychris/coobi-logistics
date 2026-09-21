# Stream processor (MVP-2)

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
                (invalid events)             (one alert per crossing)
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
| `COOBI_KAFKA_INITIALIZATION_ENABLED` | Provision the Kafka topics on startup; `false` runs the service without a broker | `true` | boolean |

Every other setting lives in
[`application.yml`](../services/stream-processor/src/main/resources/application.yml) and can
still be overridden with Spring's relaxed binding, for example
`COOBI_KAFKA_TOPICS_ALERT=logistics.alert.test.v1`, `COOBI_KAFKA_TOPICS_PARTITIONS=12` or
`SPRING_KAFKA_STREAMS_AUTO_STARTUP=false`.

An invalid value aborts startup instead of degrading silently: the speed limit must be a
finite number greater than zero, and the topic names, the partition count and the
replication factor are validated while the application context is built.

The stopped-vehicle thresholds documented in `.env.example`
(`STOPPED_WINDOW_SECONDS`, `MOVEMENT_THRESHOLD_METERS`) are reserved for MVP-3 and are not
read by this service yet.

## Topics

| Topic | Direction | Purpose |
| --- | --- | --- |
| `logistics.vehicle.location.v1` | consumed | Versioned telemetry from the generator |
| `logistics.vehicle.location.dlq.v1` | produced | Records that could not be accepted |
| `logistics.alert.v1` | produced | Speeding alerts |

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
`SPEEDING_DETECTED` and `VEHICLE_STOPPED` as `VEHICLE_STOPPED_DETECTED`. The second type is
part of the contract but is not produced yet: MVP-3 owns the stopped-vehicle detection.
The `timestamp` is the timestamp of the telemetry event that crossed the limit, and
`threshold` is the configured `SPEED_LIMIT` in km/h, written as a JSON number.

## Observability

| Signal | Where |
| --- | --- |
| Health | `GET http://localhost:8081/actuator/health` |
| Info | `GET http://localhost:8081/actuator/info` |
| Speeding detections | `INFO` log line per alert, with the vehicle, the speed and the threshold |
| Rejections | `WARN` log line per record routed to the dead letter topic |
| Recovery | `DEBUG` log line when a vehicle returns below the limit |

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

The suite runs without a broker. The topology is driven with `TopologyTestDriver`, which
covers the validation outcomes, the dead letter envelope, the speed-limit transitions and
the continuity of valid records after an invalid one; the remaining tests cover binding and
fail-fast configuration, topic provisioning against a mocked admin client and the
serialized shape, versioning and immutability of both contracts.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Startup fails with `kafka topic initialization failed` | No broker on `KAFKA_BOOTSTRAP_SERVERS`. Start `docker compose up -d`, or disable provisioning for offline work. |
| `/actuator/health` reports `DOWN` | The Kafka health indicator cannot reach the broker. Start the broker, or set `MANAGEMENT_HEALTH_KAFKA_ENABLED=false`. |
| No alerts although the generator is running | The simulated fleet peaks at 110 km/h, below the 120 km/h default. Lower `SPEED_LIMIT`, for example `$env:SPEED_LIMIT="90"`. |
| Records land in the dead letter topic | Their `error` field names the rejected field. The most common cause is a payload written by a different schema version. |
| `mvnw.cmd` fails under PowerShell 7 | Use an installed Maven as an equivalent fallback: `mvn -f services/stream-processor/pom.xml test`. |
