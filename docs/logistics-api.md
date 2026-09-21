# Logistics REST API (MVP-5 and MVP-6)

`services/logistics-api` is the read side of the stack: a Spring Boot service that answers the
operator views - the fleet, the alerts and the live statistics - from the state the stream
processor has already derived and persisted. It owns no migration and writes no row: it maps
the tables of MVP-4 and serves them as JSON, and it also serves the two browser streams of
MVP-6 - the events that happen while a browser is connected, and the live statistics - as
Server-Sent Events.

```text
browser / client
      │  HTTP  :8082
      │    ├─ JSON, one request per view
      │    └─ SSE, one connection per stream (the dashboard of MVP-7)
      ▼
logistics-api  ──reads──▶  PostgreSQL  ◀──writes──  stream-processor (MVP-4)
      │
      └──reads the Kafka Streams counters over the Actuator metrics of the processor
```

## Quick start

Requirements: JDK 21 and the PostgreSQL of the local stack. The repository ships a Maven
wrapper, so no local Maven installation is needed.

```powershell
# 1. Infrastructure. Copy the template once and set a local POSTGRES_PASSWORD.
Copy-Item .env.example .env
docker compose up -d

# 2. Create the schema: the API reads it and never creates it. Run the processor once, or
#    apply the migrations of services/stream-processor with your own pipeline.
.\services\stream-processor\mvnw.cmd -f services/stream-processor/pom.xml spring-boot:run

# 3. Run the API. PostgreSQL is reached on localhost:5432 by default.
.\services\logistics-api\mvnw.cmd -f services/logistics-api/pom.xml spring-boot:run
```

```bash
cp .env.example .env
docker compose up -d
./services/stream-processor/mvnw -f services/stream-processor/pom.xml spring-boot:run
./services/logistics-api/mvnw -f services/logistics-api/pom.xml spring-boot:run
```

The service listens on `http://localhost:8082`, next to the generator on 8080 and the processor
on 8081. Override the port with `SERVER_PORT`.

## Configuration

Configuration is read from the process environment. The variables below are the documented
contract and are kept in sync with `.env.example` and README.md.

| Variable | Purpose | Default | Units |
| --- | --- | --- | --- |
| `POSTGRES_DB` | Database name, shared with the Compose stack | `logistics` | database name |
| `POSTGRES_USER` | Database role of the service | `logistics` | role name |
| `POSTGRES_PASSWORD` | Password of that role, the same value the Compose stack uses | (none) | string |
| `SPRING_DATASOURCE_URL` | JDBC URL, for a database that is not the local stack | `jdbc:postgresql://localhost:5432/${POSTGRES_DB}` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Overrides the database role | `POSTGRES_USER` | role name |
| `SPRING_DATASOURCE_PASSWORD` | Overrides the database password | `POSTGRES_PASSWORD` | string |
| `STREAM_PROCESSOR_METRICS_URL` | Actuator metrics endpoint of the stream processor, read by `/api/v1/statistics` | `http://localhost:8081/actuator/metrics` | URL |
| `COOBI_STREAM_EVENTS_POLL_INTERVAL` | How often `/api/v1/stream/events` reads the read model for what is new since the previous tick | `1s` | duration (`250ms`, `2s`) |
| `COOBI_STREAM_EVENTS_MAX_ALERTS_PER_POLL` | Most alerts one tick of the event stream may carry | `20` | alerts (count) |
| `COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL` | Most vehicle states one tick of the event stream may carry | `30` | vehicle states (count) |
| `COOBI_STREAM_STATISTICS_INTERVAL` | How often `/api/v1/stream/statistics` reads and sends the live statistics | `1s` | duration (`250ms`, `2s`) |
| `COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS` | Browsers one stream serves at a time; the next connection is answered `503` instead of being held | `32` | connections (count) |

The `POSTGRES_*` variables are the ones `.env` already holds for the Compose service, so one
file configures the stack and every service. The `SPRING_DATASOURCE_*` names are the standard
Spring overrides and win over them. The `COOBI_STREAM_*` variables are the ones that bound what
a browser may receive and what one may cost; they are documented in `.env.example` too, and
their placeholders in `application.yml` are asserted by `ApiEnvironmentContractTest`.

Two client settings have no variable of their own because they are properties of the response
format rather than of a deployment - the frames held for one browser that has not read them
(`coobi.stream.client.buffer-size`, `256`) and how long a quiet connection may stay silent
(`coobi.stream.client.heartbeat-interval`, `15s`). They are documented in
[Streams](#streams) and overridable with relaxed binding, for example
`COOBI_STREAM_CLIENT_BUFFER_SIZE=64`.

Every other setting lives in
[`application.yml`](../services/logistics-api/src/main/resources/application.yml) and can still
be overridden with Spring's relaxed binding, for example
`COOBI_STATISTICS_STREAM_PROCESSOR_PROCESSED_EVENTS_METRIC` or `SERVER_PORT=9082`.

Connection pool and schema decisions are deliberately read-only:

| Setting | Value | Why |
| --- | --- | --- |
| `spring.datasource.hikari.read-only` | `true` | The API is a reader; a write is a defect, not a configuration choice |
| `spring.datasource.hikari.maximum-pool-size` | `5` | Page requests, not a fan-out writer |
| `spring.jpa.hibernate.ddl-auto` | `none` | The schema is created by the migrations of the stream processor and by nothing else |
| `spring.jpa.open-in-view` | `false` | No lazy entity is read outside a transaction |
| Flyway | not a dependency | A second migration engine would mean two schema histories for one database |

An invalid statistics address - a value that is not a URL, an empty metric name - aborts
startup instead of degrading silently. An address that is unreachable is not a configuration
error: the statistics endpoint stays available and reports those fields as absent.

## Endpoints

Base path: `/api/v1`. Every response is JSON, except the two streams, which are
`text/event-stream` responses that stay open ([Streams](#streams)).

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/vehicles` | One page of the fleet, newest telemetry first |
| `GET` | `/api/v1/vehicles/{vehicleId}` | Latest known state of one vehicle |
| `GET` | `/api/v1/alerts` | One page of alerts, newest first |
| `GET` | `/api/v1/alerts/{id}` | One stored alert |
| `GET` | `/api/v1/statistics` | Live statistics of the stack |
| `GET` | `/api/v1/stream/events` | Server-Sent Events: the alerts and vehicle states that happen while a browser is connected, sampled |
| `GET` | `/api/v1/stream/statistics` | Server-Sent Events: the live statistics, re-sent at the configured interval |
| `GET` | `/actuator/health` | Health of the service, including the database |
| `GET` | `/actuator/info` | Build and milestone information |

### Paging

The two collection endpoints share the same paging parameters:

| Parameter | Default | Accepted range | Notes |
| --- | --- | --- | --- |
| `page` | `0` | `0` and above | Zero-based page index |
| `size` | `20` | `1` to `100` | Above the maximum the request is rejected, not silently clamped |

The order of a page is fixed by the endpoint, not by the caller: vehicles by `lastUpdate`
descending and then by vehicle id, alerts by `occurredAt` descending and then by stored id. The
tie breaker matters - without it a page boundary could drop or repeat a record whose timestamp
is shared - and the fixed order is the one the indexes of the schema serve.

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

### Vehicles

`GET /api/v1/vehicles` accepts one optional filter:

| Parameter | Values | Notes |
| --- | --- | --- |
| `status` | `MOVING`, `STOPPED` | Omitted or empty means every vehicle |

`GET /api/v1/vehicles/{vehicleId}` answers the same object for one vehicle and reports `404`
when that vehicle has never sent telemetry.

```json
{
  "vehicleId": "TRUCK-00001",
  "latitude": 39.4699,
  "longitude": -0.3763,
  "speed": 80.0,
  "heading": 214.5,
  "status": "MOVING",
  "lastUpdate": "2026-09-21T09:15:00Z",
  "updatedAt": "2026-09-21T09:15:00.123Z"
}
```

The field names and units are the ones of the version-1 location contract the services share -
`speed` in kilometres per hour, `heading` in degrees clockwise from north - so a client reads
the same names in the stream and in the API. The two timestamps are kept apart on purpose:
`lastUpdate` is when the vehicle was last heard from, `updatedAt` is when the read model was
last written, so a stalled vehicle is distinguishable from a stale row.

A vehicle that has never sent telemetry is unknown rather than empty. The row this endpoint
reads is written in the same transaction as the registry row it belongs to, so the fleet view
and the registry agree.

### Alerts

`GET /api/v1/alerts` accepts three filters that combine independently, in any subset:

| Parameter | Values | Notes |
| --- | --- | --- |
| `vehicleId` | any vehicle id | Omitted or blank means every vehicle |
| `type` | `SPEEDING_DETECTED`, `VEHICLE_STOPPED_DETECTED` | Omitted means every type |
| `severity` | `WARNING` | Omitted means every severity |

Filter values are the contract values, written exactly as the alert contract writes them: a
`status`, `type` or `severity` outside those values is rejected with `400` and a message that
names what the endpoint accepts.

`GET /api/v1/alerts/{id}` answers the same object for one alert and reports `404` when no alert
has that key, and `400` when the key is not a number.

```json
{
  "id": 7,
  "eventId": "2f8d3c1e-0b52-4a4f-9d76-8e13a3f0c111",
  "vehicleId": "TRUCK-00001",
  "type": "SPEEDING_DETECTED",
  "severity": "WARNING",
  "occurredAt": "2026-09-21T09:15:00Z",
  "createdAt": "2026-09-21T09:15:00.123Z",
  "metadata": {
    "speed": 131.5,
    "threshold": 120.0
  }
}
```

`eventId` is the idempotency key of the alert contract: the same event stored twice is one row,
so a client can use it to deduplicate what it has already seen. `metadata` is the `AlertData`
of the contract embedded as JSON rather than as a string of JSON - the measured value and the
threshold that produced the alert - so no client has to parse twice.

### Statistics

`GET /api/v1/statistics` takes no parameter and reports the live state of the stack.

```json
{
  "processedEvents": 12938281,
  "eventsPerSecond": 12492,
  "activeVehicles": 5000,
  "alertsGenerated": 281,
  "uptimeSeconds": 8271
}
```

Every value has a named source, and no value is estimated:

| Field | Source | When it is `null` |
| --- | --- | --- |
| `processedEvents` | The Kafka Streams counter of the stream processor, read over its Actuator metrics endpoint and summed over its stream threads | The processor is unreachable, or does not publish the metric |
| `eventsPerSecond` | The average rate between the two latest readings of that counter, in this instance | No second reading yet (the first response after a start), or the counter restarted with the processor |
| `activeVehicles` | `SELECT COUNT(*)` of the vehicles whose latest state is `MOVING` | Never |
| `alertsGenerated` | `SELECT COUNT(*)` of the rows currently stored in `alerts` | Never |
| `uptimeSeconds` | The time this API instance has been serving | Never |

Two consequences are visible in the response rather than hidden behind it. First, a counter
that cannot be read is reported as `null`, never as a number that looks live and is not, so a
client draws a gap instead of a false value. Second, the rate is a real observation - events
between two readings divided by the time between them - which is why it is absent until the
second reading, and why it averages the whole window when a reading in between failed.

The counters are read per API instance: two instances report the same `processedEvents`, since
that number belongs to the processor, and each derives its own rate from its own readings.

## Streams

Two endpoints answer with Server-Sent Events instead of with a document: the events that happen
while a browser is connected, and the live statistics. Both are one long-lived response - the
request is answered as soon as a connection is accepted, and the connection ends when the
browser closes it, when a write to it fails, or when the service shuts down, never because a
timeout expired.

| Endpoint | Carries | Rate |
| --- | --- | --- |
| `GET /api/v1/stream/events` | `alert` events for the alerts the processor stored, `vehicle` events for the vehicle states it updated | One tick every `coobi.stream.events.poll-interval` (`COOBI_STREAM_EVENTS_POLL_INTERVAL`, `1s`) |
| `GET /api/v1/stream/statistics` | `statistics` events, the object `GET /api/v1/statistics` answers | One tick every `coobi.stream.statistics.interval` (`COOBI_STREAM_STATISTICS_INTERVAL`, the documented `1s` default) |

### The frames

A connection opens with one frame that carries no event:

```text
retry:3000
:connected

```

`retry` tells the browser to wait three seconds before reconnecting, which is the normal answer
here: a connection that ends - a proxy with an idle timeout, a restart - is reopened by the
browser on its own, and the stream then continues at the live edge while the history stays a
page fetched over REST. The comment is what commits the response, so a browser reports the
connection as open immediately, instead of only when the first event happens.

An event names its family and carries the response of the REST endpoint that owns the same
object, so a client reads the same field names in the stream and in the API and dispatches on a
name instead of on the shape of a document:

```text
event:alert
data:{"id":7,"eventId":"2f8d3c1e-0b52-4a4f-9d76-8e13a3f0c111","vehicleId":"TRUCK-00001", ...}

event:vehicle
data:{"vehicleId":"TRUCK-00002","latitude":39.4699,"longitude":-0.3763, ...}
```

A frame is one `event:` line, one `data:` line and the blank line that closes it; the payloads
above are abbreviated, and they are the objects of [Vehicles](#vehicles) and
[Alerts](#alerts) field for field. A connection that has nothing else to say sends a comment,
`:keep-alive`, after `coobi.stream.client.heartbeat-interval` (`15s`), which keeps an
intermediary from closing a stream that is merely quiet. A comment is not an event: a client
ignores it.

### What a browser may receive

The stream is a view of the pipeline, not a pipe from it. The topic is never forwarded - there
is no browser-facing publisher, and this service deliberately does not subscribe to Kafka -
so the events a browser may see are the ones the processor has already stored: the alerts it
accepted and the vehicle states it overwrote. A tick reads what was stored since the previous
one and sends at most `max-alerts-per-poll` alerts and `max-vehicles-per-poll` vehicle states,
oldest first, so what one browser receives is a property of this configuration and not of the
load of the stack: a second in which the pipeline processed thousands of records is still one
tick's worth of events.

A burst that does not fit in one tick is sampled rather than queued - the cursor moves to the
newest record of the batch, so the newest events are the ones sent and the browser stays at the
live edge instead of being shown an hour of history one tick at a time. A vehicle whose state
was overwritten twice between two ticks is one event, not two: the read model keeps one row per
vehicle (MVP-4), and the stream reports what the state is now rather than what it was.

The two families are positioned differently, and it shows in one case. An alert is positioned by
its stored key, which is generated in order, so every alert stored after the previous tick is
either sent or deliberately skipped. A vehicle is positioned by the event time of its telemetry,
so two vehicles whose telemetry carries exactly the same timestamp can leave one of them to the
REST view: the stream is a sample, and the row it skipped is still a page of
`/api/v1/vehicles`.

The history is not streamed. The feed is placed at the live edge when the first browser
connects to a quiet stream, and the past is what `/api/v1/alerts` and `/api/v1/vehicles` serve
as pages.

### What a browser may cost

Bounded on purpose, in three places, because a browser is a client this service does not
control:

| Bound | Setting | Behaviour when it is reached |
| --- | --- | --- |
| Connections per stream | `coobi.stream.client.max-subscribers` (`COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS`, `32`) | The next connection is answered `503` with a problem detail instead of being queued: the service refuses a connection it cannot serve, and the browser may open it again when a slot is free |
| Frames held per connection | `coobi.stream.client.buffer-size` (`256`) | The oldest frame of that connection is dropped to make room for the newest, so a browser that stopped reading - a suspended laptop, a stalled network - costs the service a fixed amount of memory and not a growing one; the client sees a gap |
| Silence before a heartbeat | `coobi.stream.client.heartbeat-interval` (`15s`) | A comment frame is written, which is what keeps intermediaries from closing a quiet connection |

Each connection is drained by a virtual thread of its own, so a browser that reads slowly blocks
nothing but itself: the ticker never waits on a socket, and no connection can slow another one
down. An event is serialized once per tick and not once per connection, so the cost of a tick
grows with the size of the event and not with the number of browsers.

Nothing at all runs while nobody is connected. The ticker of a stream is created with the first
connection of a quiet period and cancelled with the last one, so an API nobody watches reads
neither the read model nor the statistics, and its event rate is a rate that is never exceeded
rather than a poll that always runs.

### When the browser goes away

A disconnect is noticed by the next write that fails on the connection, or by the completion
callback of the framework, whichever comes first; the connection is then removed, its pump
thread is woken, and the stream goes back to having no ticker once it is the last one. A
browser that closed its page is not a failure of this service, so it is logged at `DEBUG` and
the connection is not held until a timeout.

## Errors

Every failure is answered with an RFC 9457 problem detail - never with a stack trace, and never
with the default body of the framework.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "the value 'FLYING' is not valid for 'status'; accepted values are MOVING, STOPPED",
  "instance": "/api/v1/vehicles"
}
```

| Status | When | Body |
| --- | --- | --- |
| `400` | An unknown filter value, paging outside the documented range, a path variable of the wrong type | Problem detail naming the parameter and what it accepts |
| `404` | A vehicle that has never sent telemetry, an alert that does not exist, an unknown path | Problem detail naming the resource |
| `500` | Anything else | Problem detail with a generic message; the cause is logged with the request path |

## Observability

| Signal | Where |
| --- | --- |
| Health | `GET http://localhost:8082/actuator/health`, including the database indicator |
| Info | `GET http://localhost:8082/actuator/info` |
| Statistics without the processor | One `WARN` log line, then `DEBUG` until it answers again |
| A metric the processor does not publish | `DEBUG` log line naming the metric and the response status |
| Unreadable alert metadata | `WARN` log line naming the alert; the row is reported as text and the page is still served |
| Browsers connected to a stream | Gauge `coobi.stream.subscribers`, tagged `stream=events` or `stream=statistics`, at `GET http://localhost:8082/actuator/metrics/coobi.stream.subscribers` |
| Frames a browser did not read in time | Counter `coobi.stream.dropped`, tagged by stream, plus a `DEBUG` log line naming the connection |
| A stream that refuses a connection | `503` problem detail naming the stream and its maximum; the capacity is the configured one |
| A browser that went away, an event that cannot be serialized | `DEBUG` line for the first, `WARN` line naming the event type for the second; the stream continues in either case |
| Unhandled failure | `ERROR` log line with the request path |

## Tests

```powershell
.\services\logistics-api\mvnw.cmd -f services/logistics-api/pom.xml test
```

```bash
./services/logistics-api/mvnw -f services/logistics-api/pom.xml test
```

The suite needs neither a database server nor a running processor. The endpoint tests drive the
controllers with a mocked service and cover the contract: the paging metadata, every filter,
the `404` of an unknown resource and the `400` of an unknown filter value, a negative page, a
size above the maximum and a path variable of the wrong type. The read-model tests run the
queries against an in-memory database in PostgreSQL mode, so filtering, ordering and paging are
verified against real SQL rather than against a double. The statistics tests cover the
summation of the per-thread counter, a derived rate, an unreachable processor, a metric that is
not published and a counter that restarted; another group boots the whole service and checks
that it reports itself healthy and answers live statistics while the processor is unreachable.

The stream tests drive both endpoints without a browser and without a clock: the ticks are run
by the test, so what a stream publishes, the interval it is scheduled with, the frames a
connection receives, the sampling of a burst, the buffer of a connection that fell behind, the
`503` of a stream at capacity and the release of a connection that went away are all asserted
deterministically. `StreamingEndpointsTest` then opens the two streams over real HTTP, with the
intervals configured to milliseconds, and asserts the response headers, the frames, the delay
between two statistics, the sampling of a burst of ten alerts and the fact that a disconnect is
given up without a timeout.

`SchemaContractTest` closes the gap that matters most here: this service maps tables it does
not own, so the test reads the migrations of the stream processor and asserts that the columns
the entities declare are exactly the columns those migrations create. A column renamed in the
service that owns the schema fails the build of the service that would break.

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| `GET /api/v1/vehicles` answers `500` with `relation "vehicle_latest_state" does not exist` | The schema was never created. Run the stream processor once so its migrations apply, or apply them with your pipeline. |
| `processedEvents` is `null` while the stack runs | The processor is unreachable at `STREAM_PROCESSOR_METRICS_URL`, or its metric name differs. Check `GET http://localhost:8081/actuator/metrics` and point the variable at the metric it publishes. |
| `eventsPerSecond` is `null` on the first request | Expected: a rate needs two readings of a cumulative counter. Poll again after a few seconds. |
| A stream stays silent while the stack is busy | The streams carry what happens while a browser is connected, and only what is stored: a tick with nothing new sends nothing. The connection is still open - a `:keep-alive` comment arrives after every `coobi.stream.client.heartbeat-interval`. |
| A stream reconnects every few seconds behind a proxy | An intermediary is closing an idle connection. Lower `COOBI_STREAM_CLIENT_HEARTBEAT_INTERVAL` so the comment arrives before it does, for example `5s`. |
| `GET /api/v1/stream/events` answers `503` | The stream already serves as many browsers as `COOBI_STREAM_CLIENT_MAX_SUBSCRIBERS` allows. Close a tab, or raise the limit; the service refuses the connection rather than holding one it cannot serve. |
| The dashboard receives fewer events than the pipeline processed | Expected: a tick is bounded by `COOBI_STREAM_EVENTS_MAX_ALERTS_PER_POLL` and `COOBI_STREAM_EVENTS_MAX_VEHICLES_PER_POLL`, and a burst is sampled instead of queued. Raise them if a view needs more per tick, and read the history over REST. |
| Startup fails with `Failed to bind properties under 'coobi.statistics'` | The statistics address or the metric name is missing or not a URL. The service refuses to start instead of answering `null` forever. |
| `/actuator/health` reports `DOWN` | The database indicator cannot reach PostgreSQL. Start `docker compose up -d`, and check that `POSTGRES_PASSWORD` is set in the shell that runs the service. |
| Every alert answers with `metadata` as a JSON string | The row was written by something other than the processor migration, so its `metadata` is a JSON string rather than a JSON object. Inspect the row; the API reports what it finds instead of failing the page. |
| Port 8082 is already in use | Another instance is running, or the port is taken. Set `SERVER_PORT` to a free port. |
| `mvnw.cmd` fails under PowerShell 7 | Use an installed Maven as an equivalent fallback: `mvn -f services/logistics-api/pom.xml test`. |
