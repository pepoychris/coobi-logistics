---
layout: page
title: "Observability"
description: "Micrometer, Prometheus and Kafka client metrics of the local stack."
---

# Observability

The pipeline answers two questions about itself: how much it is doing, and what it costs.
This document is the contract of both answers - the metrics each service produces, the
Prometheus that scrapes them, and the queries worth running against a benchmark.

```text
event-generator  :8080/actuator/prometheus  ---+
                                               |    scrape every 15 s
stream-processor :8081/actuator/prometheus  ---+--> Prometheus :9090 -> local volume
```

## Running Prometheus

Prometheus is part of the local stack, so the usual startup command is enough:

```powershell
Copy-Item .env.example .env
# open .env and set POSTGRES_PASSWORD to a local password
docker compose up -d
docker compose ps
```

```bash
cp .env.example .env
docker compose up -d
docker compose ps
```

| Item | Value |
| --- | --- |
| UI and API | `http://localhost:9090` (loopback only, like the rest of the stack) |
| Health | `GET http://localhost:9090/-/healthy`, the endpoint of the container health check |
| Configuration | `infrastructure/prometheus/prometheus.yml`, mounted read-only |
| Scrape interval | `15s`, with a `10s` timeout |
| Retention | `15d`, in the `prometheus-data` volume |
| Scrape targets | `event-generator:8080` and `stream-processor:8081`, the service names of the stack |

The two application services run as containers of the same stack, so a scrape target is the
service name `compose.yml` gives them, and the scrape needs neither a published host port nor
the host gateway. The names are written out in the configuration file rather than interpolated
from the environment, because Prometheus expands `${VAR}` references of that file in
`external_labels` and nowhere else: the single `PROMETHEUS_HOST` this repository used to ship
in a target was read by the container, never expanded, and left both targets `down`.
To scrape a service somebody started from the host instead, change its target to
`host.docker.internal:8080` or `:8081`; `compose.yml` maps that name on a Linux engine as well
as on Docker Desktop.

Both services must be running for their targets to be healthy. Prometheus starts without
them, so a target is `down` until the service it describes is up; that is the expected state
of a machine that has only started the infrastructure:

```powershell
# Scrape health of every target
(Invoke-RestMethod "http://localhost:9090/api/v1/targets").data.activeTargets |
    Select-Object scrapeUrl, health, lastError
```

```bash
curl -s http://localhost:9090/api/v1/targets | jq '.data.activeTargets[] | {scrapeUrl, health, lastError}'
```

The file is the single place the targets are defined, and `compose.yml` mounts it read-only, so
what Prometheus scrapes is what the repository says it scrapes.

## Enabling the endpoint

Two things have to be true for `/actuator/prometheus` to answer:

1. `io.micrometer:micrometer-registry-prometheus` is on the classpath of the service, which
   is what makes the exposition format available;
2. the export is switched on. The export of a registry is opt-in, so both services state it
   instead of relying on the auto-configuration being picked up by accident:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  prometheus:
    metrics:
      export:
        enabled: true
```

The same classpath gives the service `/actuator/metrics`, the JSON view that reports one
metric at a time. The logistics-api keeps only that view: it is not a scrape target, because
the pipeline metrics and the Kafka client metrics are produced by the two services that touch
Kafka.

## Pipeline metrics

Five meters describe the telemetry the pipeline consumes and the alerts it produces. Their
names are the contract: they are the names in `/actuator/metrics`, and Prometheus renders
them with any dot replaced by an underscore.

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `logistics_events_received_total` | Counter | - | Records this processor consumed from `logistics.vehicle.location.v1` |
| `logistics_events_processed_total` | Counter | - | Consumed records that passed validation and are processed by the detections |
| `logistics_events_failed_total` | Counter | - | Consumed records that failed validation and are routed to the dead letter topic |
| `logistics_alerts_generated_total` | Counter | `type` = `SPEEDING` or `VEHICLE_STOPPED` | Alerts generated and forwarded to `logistics.alert.v1` |
| `logistics_event_processing_duration` | Timer | - | Time one processing step of the topology takes |

The counters are incremented on the path they describe, never at startup, so a counter that
stays at zero means no record reached that stage:

| Path | Counter increments | Timed steps |
| --- | --- | --- |
| A record is consumed and accepted | `received`, `processed` | validation, speeding detection, vehicle-state detection |
| A record is consumed and rejected | `received`, `failed` | validation, dead letter mapping |
| A detection crosses into an alert | `alerts` (`type`) | - |

Two invariants follow from the wiring, and a benchmark can assert them:

```text
logistics_events_processed_total + logistics_events_failed_total = logistics_events_received_total
```

```text
logistics_event_processing_duration_seconds_count
    = 3 per accepted record + 2 per rejected record
```

The timer is rendered by Prometheus with its base unit in the name, which is the convention
for a duration:

| Prometheus series | Meaning |
| --- | --- |
| `logistics_event_processing_duration_seconds_count` | Number of processing steps that were timed |
| `logistics_event_processing_duration_seconds_sum` | Total time those steps took, in seconds |
| `logistics_event_processing_duration_seconds_max` | Longest single step, in seconds |

`/actuator/metrics/logistics_event_processing_duration` reports the same timer under its
contract name, with the `COUNT`, `TOTAL_TIME` and `MAX` statistics.

The event-generator counts its own side of the pipeline: every publication confirmed by the
broker and every failure, tagged with the topic and with the result. The failure counter is
the signal that a producer is being rejected while the service stays up.

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `coobi_generator_events_total` | Counter | `result` = `published` or `failed`, `topic` | Telemetry events confirmed by the broker, and events that could not be published |

## Kafka metrics

The Kafka clients publish their own metrics next to the two sets above. They are bound by
Micrometer, not by this repository, so the names below are the reported ones rather than
names this project chose; `/actuator/metrics` on a running service is the authority for the
exact set of its broker version.

The stream processor binds them through Spring Boot, which attaches Micrometer's Kafka
Streams binder to the streams of the service. The metrics of the streams, of the consumers
that read the location topic and of the producer that writes the alert topic all come from
that binder:

| Micrometer name | Prometheus series | Signal |
| --- | --- | --- |
| `kafka.stream.thread.process.total` | `kafka_stream_thread_process_total` | Records the stream threads have processed |
| `kafka.stream.thread.process.rate` | `kafka_stream_thread_process_rate` | Processing rate of the streams |
| `kafka.consumer.fetch.manager.records.consumed.total` | `kafka_consumer_fetch_manager_records_consumed_total` | Records consumed from the location topic |
| `kafka.consumer.fetch.manager.records.consumed.rate` | `kafka_consumer_fetch_manager_records_consumed_rate` | Records per second consumed |
| `kafka.consumer.fetch.manager.records.lag.max` | `kafka_consumer_fetch_manager_records_lag_max` | Consumer lag, where the broker reports it |
| `kafka.stream.thread.commit.total` | `kafka_stream_thread_commit_total` | Commits of the streams |
| `kafka.stream.alive.stream.threads` | `kafka_stream_alive_stream_threads` | Stream threads that are alive |
| `kafka.producer.record.send.rate` | `kafka_producer_record_send_rate` | Records per second written to the alert topic |
| `kafka.producer.record.error.total` | `kafka_producer_record_error_total` | Producer errors |

The event-generator produces the same Kafka client metrics for its own producer. Spring Boot
applies its producer customizers - the binder among them - to the factory the
auto-configuration builds, and this service declares its own factory to pin the JSON
serializers, so `KafkaClientConfiguration` applies the customizers of the context to that
factory. Without that, the producer would publish none of the metrics below.

| Micrometer name | Prometheus series | Signal |
| --- | --- | --- |
| `kafka.producer.record.send.total` | `kafka_producer_record_send_total` | Records sent |
| `kafka.producer.record.send.rate` | `kafka_producer_record_send_rate` | Records per second sent |
| `kafka.producer.record.error.total` | `kafka_producer_record_error_total` | Send failures |
| `kafka.producer.record.error.rate` | `kafka_producer_record_error_rate` | Failures per second |
| `kafka.producer.requests.in.flight` | `kafka_producer_requests_in_flight` | Requests waiting for a response |
| `kafka.producer.buffer.available.bytes` | `kafka_producer_buffer_available_bytes` | Room left in the send buffer |

## Queries for a benchmark run

Every sample carries the `application` label of the service that produced it
(`event-generator` or `stream-processor`), and the `job` label of its scrape job, so one
database can answer both halves of the pipeline without knowing which target a series came
from. Rates are computed from the cumulative counters with `rate()`, which is why no service
reports a rate of its own.

| Question | Query |
| --- | --- |
| Events per second reaching the processor | `sum(rate(logistics_events_received_total[1m]))` |
| Events per second the pipeline processed | `sum(rate(logistics_events_processed_total[1m]))` |
| Events per second that failed validation | `sum(rate(logistics_events_failed_total[1m]))` |
| Alerts per second, per detection | `sum(rate(logistics_alerts_generated_total[1m])) by (type)` |
| Average time of one processing step | `sum(rate(logistics_event_processing_duration_seconds_sum[1m])) / sum(rate(logistics_event_processing_duration_seconds_count[1m]))` |
| Records per second processed by the stream threads | `sum(rate(kafka_stream_thread_process_total[1m]))` |
| Consumer lag of the location topic | `sum(kafka_consumer_fetch_manager_records_lag_max)` |
| Producer errors of the pipeline | `sum(rate(kafka_producer_record_error_total[1m]))` |
| Records per second the generator sent | `sum(rate(kafka_producer_record_send_total[1m]))` |
| Telemetry the generator could not publish | `sum(rate(coobi_generator_events_total{result="failed"}[1m]))` |

The same queries run against the API of Prometheus, which is useful while the UI is not open:

```powershell
Invoke-RestMethod "http://localhost:9090/api/v1/query?query=sum(rate(logistics_events_processed_total[1m]))"
```

## What each service reports

| Service | Health | Metrics view | Prometheus | Custom signals |
| --- | --- | --- | --- | --- |
| event-generator | `/actuator/health` (8080) | `/actuator/metrics` | `/actuator/prometheus` | `coobi_generator_events_total`, `kafka.producer.*` |
| stream-processor | `/actuator/health` (8081) | `/actuator/metrics` | `/actuator/prometheus` | `logistics_events_*`, `logistics_alerts_generated_total`, `logistics_event_processing_duration`, `kafka.stream.*`, `kafka.consumer.*`, `kafka.producer.*` |
| logistics-api | `/actuator/health` (8082) | `/actuator/metrics` | not a scrape target | `coobi.stream.*` of the browser streams |

`/actuator/metrics` also reports the JVM, the HTTP server and the connection pool of each
service, because Spring Boot binds those without any configuration. `/actuator/prometheus`
returns the same set in the exposition format, and is the only view Prometheus reads.

## Tests

The metrics are covered where they are produced rather than asserted from a description:

| Test | What it pins |
| --- | --- |
| `TelemetryMetricsTest` | The five metric names, their types, their descriptions and the `type` tag of the alert counter |
| `LogisticsMetricsTopologyTest` | The counters, the timed steps and the Prometheus exposition format of a topology driven through both paths |
| `StreamProcessorApplicationTests` | `/actuator/prometheus` answering with the contract names, and the Kafka Streams binder of the service |
| `KafkaProducerMetricsTests` | The `kafka.producer.*` metrics of a producer the generator's factory really created |
| `KafkaClientConfigurationTest` | The customizers of the context being applied to the declared factory, serializers included |
| `EventGeneratorApplicationTests` | `/actuator/prometheus` answering with the generator counters, and the producer binder being attached |

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| A target is `down` in Prometheus | The service it names is not running, or its target was edited to an address that does not answer. `docker compose ps` shows whether the service is up, and `docker compose logs <service>` why it is not |
| A target is `down` with a connection error on Linux | The target was pointed at `host.docker.internal` for a service running on the host, and the container cannot reach it. `compose.yml` maps that name to the host gateway for that reason; check that the mapping survived any local override of the file |
| `/actuator/prometheus` answers `404` | The registry is not exported. The dependency and `management.prometheus.metrics.export.enabled` are both required; a missing one makes the endpoint disappear rather than report an empty body |
| A counter of the contract is missing | The service producing it is not running that stage. A counter is registered when the service starts and incremented when a record reaches it, so a missing metric means a version mismatch, not an idle pipeline |
| A metric appears in `/actuator/metrics` but not in Prometheus | The registry renames it. Check its name in the exposition format: dots become underscores, a timer carries its base unit (`_seconds`) and a counter ends in `_total` |
