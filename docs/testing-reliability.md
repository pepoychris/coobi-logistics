# Testing and reliability (MVP-9)

This document is the testing contract of the repository: which tests exist, what each of
them proves, how to run them, and what a machine without Docker does. The milestone it
describes is MVP-9 of the roadmap.

The suite has three layers, and every layer has a job the others cannot do:

| Layer | Where | Needs | Job |
| --- | --- | --- | --- |
| Unit | `src/test/java` of every service | nothing but the JVM | All behaviour that can be decided in-process: validation, simulation, the two state machines, distance, DTO mapping, statistics |
| Container integration tests | `src/it/java` of the services that own Kafka and PostgreSQL | Docker, and the images of `compose.yml` | The pieces that only fail against a real broker or a real database: the topology on a broker, the migrations and the SQL on PostgreSQL |
| Smoke test | `src/it/java` of `logistics-api` | Docker | The complete pipeline in one scenario: generated telemetry to the public API over HTTP |

## The unit suite (MVP-9.1)

The unit suite is the ordinary suite of each service - `mvn test` - and it is deliberately
the only layer a contributor needs to run while working: it resolves no container
dependency at all, starts nothing, and reaches no external service. It runs against the
in-memory database in PostgreSQL mode and against `TopologyTestDriver`, which runs the real
topology without a broker.

The minimum list of the milestone maps to the following tests. Every one of them is
deterministic: nothing sleeps, no test waits for a wall-clock window, and the timelines a
test needs are driven by the timestamps of the telemetry it feeds in.

| Minimum of MVP-9.1 | Evidence |
| --- | --- |
| Event validation | `event-generator`: `VehicleLocationEventValidationTest` (14). `stream-processor`: `TelemetryInspectorTest` (14), `TelemetryTopologyTest` (11, including the malformed payload, the unsupported version and the key that is not the vehicle) |
| Vehicle simulation | `event-generator`: `VehicleTelemetrySimulatorTest` (8), `VehicleSimulationSettingsTest` (10), `PublishRatePlannerTest` (7) |
| Speeding state transitions | `stream-processor`: `TelemetryTopologyTest` (one alert per crossing, no repetition while above the limit, a second alert after recovery, keyed per vehicle, the boundary value, the configured limit) |
| Stopped vehicle detection | `stream-processor`: `StoppedVehicleDetectorTest` (13) and `VehicleStateTopologyTest` (11, including one alert per window, no repetition while stopped, and a second window after driving away) |
| Distance calculations | `stream-processor`: `HaversineTest` (7), plus the accumulated-distance cases of `StoppedVehicleDetectorTest` |
| DTO mapping | `logistics-api`: `VehicleReadModelTest`, `AlertReadModelTest`, `VehicleEndpointsTest`, `AlertEndpointsTest`, `AlertMetadataTest`, `SchemaContractTest`. `stream-processor`: `VehicleStateJsonTest`, `AlertEventJsonTest`, `DeadLetterEventJsonTest` |
| Statistics calculations | `logistics-api`: `StatisticsServiceTest` and `StatisticsClient` coverage in `StreamProcessorMetricsClientTest` (the rate, the counter that goes backwards, the absent source), `StatisticsEndpointsTest` |

Reliability of the unit suite is itself verified: the two Kafka-facing services start their
context with the broker absent (`StreamProcessorApplicationTests`,
`EventGeneratorApplicationTests`), a persistence write that races another writer is retried
(`JdbcTelemetryPersistenceTest`), and the migrations are pinned to the schema the API maps
(`PersistenceMigrationTest`, `SchemaContractTest`).

## Container integration tests (MVP-9.2 and MVP-9.3)

The integration tests live in `src/it/java`, a source root that is added to the build by the
`integration-tests` profile. Nothing about them exists in a default build: the profile is
what adds the Testcontainers modules, what compiles those sources and what widens the
surefire includes to `**/*IT.java`.

Both services run the versions of the local stack: `apache/kafka:4.3.1` for the broker and
`postgres:18.6` for the database, the two images `compose.yml` uses.

| Test | What it starts | What it proves |
| --- | --- | --- |
| `stream-processor` `TelemetryPipelineIT` | Kafka | Telemetry produced through an ordinary Kafka client is processed by the real topology: crossing the speed limit publishes exactly one `SPEEDING_DETECTED` alert on `logistics.alert.v1`, keyed by the vehicle, with the measured speed, the configured threshold and the timestamp of the crossing. An invalid record reaches `logistics.vehicle.location.dlq.v1` carrying `originalEvent` byte for byte, the reason naming the field at fault, and the source topic - and the valid record published after it still produces its alert |
| `stream-processor` `PostgresPersistenceIT` | PostgreSQL | The shipped migrations applied by Flyway (the engine of the service) to real PostgreSQL, recorded in `flyway_schema_history`, with the unique constraints of the schema; the production `JdbcTelemetryPersistence` creating the vehicle and its single latest-state row, overwriting that row instead of appending history, storing an alert with its JSON document addressable as JSONB, and storing a duplicate `eventId` exactly once - the constraint refuses a second row even for a writer that bypasses the port |
| `logistics-api` `PipelineSmokeIT` | Kafka and PostgreSQL | MVP-9.4 below |

### The smoke test (MVP-9.4)

`PipelineSmokeIT` is the scenario the milestone asks for, with every component being the real
one:

```text
Generated telemetry -> Kafka -> stream processor -> PostgreSQL -> logistics-api -> HTTP
```

* the telemetry is generated by the simulator of `event-generator` (`VehicleTelemetrySimulator`
  with a clock the test moves) and expressed in its version-1 event contract;
* it is published the way the publisher of that service publishes it: one record per event,
  keyed by `vehicleId`;
* the stream processor runs as the application it is - its own context, topology, state
  stores, Flyway migrations and actuator endpoint - inside the test JVM, against the
  containers;
* the API under test is `logistics-api`, started against the same database, and it is read
  over HTTP through the endpoints a client calls.

The scenario is the smallest one that crosses both detections: the vehicle drives above the
limit at `t0`, is parked at `t0 + 400 s` and still parked at `t0 + 750 s`, which is 350 s
without movement - beyond the 300 s window of the processor.

```text
GET /api/v1/vehicles/{id}                STOPPED, with the position, speed, heading and the
                                        timestamp of the last generated event
GET /api/v1/vehicles?status=STOPPED      the same row through the fleet view and its filter
GET /api/v1/alerts?vehicleId={id}        one SPEEDING_DETECTED and one
                                        VEHICLE_STOPPED_DETECTED, with the stored metadata
GET /api/v1/statistics                   alertsGenerated counting both, activeVehicles zero,
                                        and processedEvents read from the actuator of the
                                        processor rather than invented
```

Two assumptions are worth stating, because both are properties of the test rather than of
the pipeline:

* the generator is not run as a service. Its publishing loop is bound to the wall clock and
  to a configured rate, so the test drives the simulator, the event contract and the
  publishing mechanism of that service instead; the service itself is covered by its own
  suite.
* the scenario is published once, into a database the class owns (the containers are started
  for it), which is why the counts it asserts are the counts of the scenario.

## Running the tests

Unit tests, per service - no Docker, no broker, no database:

```bash
mvn test -f services/stream-processor/pom.xml
```

Every unit suite at once, from the aggregator at the root of the repository:

```bash
mvn test
```

The container-backed integration tests, from the aggregator:

```bash
mvn -Dintegration-tests test
```

The aggregator is what makes that single command possible: the smoke test needs the stream
processor and the event generator on its test classpath, and inside one reactor build Maven
resolves them from `target/classes` of their modules. A Maven repository holds their
executable jars, which cannot be used as libraries, so the smoke test is not meant to be run
from the module directory on its own. The command stops at the `test` phase for the same
reason: `package` and the phases after it repackage those two services into executable jars,
and an executable jar is not a library. The container tests of the stream processor depend on
no other module and can also be run from their own directory:

```bash
mvn -Dintegration-tests test -f services/stream-processor/pom.xml
```

`-Pintegration-tests` activates the same profile. The property form is the documented one
because it does not warn in the modules that have no integration test.

## Without Docker

Every container test class is annotated with
`@Testcontainers(disabledWithoutDocker = true)`, so a machine without a Docker daemon skips
them with the reason Testcontainers reports instead of failing the build. A default `mvn test`
does not even compile them: the sources and the dependencies of that profile are absent, so
the unit suite of a service cannot be affected by the container tests at all.

That first property is not something this repository can demonstrate on a machine whose daemon
is running: Testcontainers resolves the daemon through a chain of strategies, so pointing
`DOCKER_HOST` at a dead address does not make the daemon unavailable while the local socket or
the Docker Desktop named pipe still answers. The skip happens on a machine where no strategy
answers - a stopped daemon, or a CI runner without Docker - and it reports the reason rather
than a failure:

```bash
mvn -Dintegration-tests test
# no Docker daemon: the unit suites run, the container tests are reported as skipped
```

What this repository does demonstrate is the other half, which is the one that protects the
default build: the profile is the only thing that adds those dependencies and compiles those
sources, so `mvn test` neither needs Docker nor resolves a container library.

## Rules the suite follows

* No fixed sleeps. A test that has to wait for something polls for it with Awaitility
  (`await().atMost(...).pollInterval(...)`), so a slow machine widens the wait instead of
  failing the build, and a failure reports the condition that never became true.
* No wall-clock windows. The stopped window of the processor is decided from the timestamps
  of the telemetry, so a five-minute window is verified in milliseconds - in the unit tests
  and in the smoke test alike.
* One image per role, the one of the local stack, so a test cannot pass against a version the
  stack does not run.
* The containers are per test class and the assertions are per vehicle, so the order in which
  the methods of a class run does not change the result.
* A test that needs a real broker says so by living in `src/it/java`; a test that does not,
  stays in the unit suite and keeps working offline.
