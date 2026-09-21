# Benchmark scenario 1000-1000-90s

| Scenario | Value |
| --- | --- |
| Vehicles | 1,000 |
| Target events per second | 1,000 |
| Measured window | 90s (90.08599996566772 seconds of wall clock) |
| Samples | 9 every 10s |
| Started (UTC) | 2026-09-21T16:14:29Z |
| Finished (UTC) | 2026-09-21T16:15:59Z |
| Local volumes reset | no |
| Dashboard in the run | no |

## Results

| Metric | Value | Unit | Status | Source |
| --- | --- | --- | --- | --- |
| Events produced | 998.72 | events/s | measured | prometheus: coobi_generator_events_total{result="published"} over the window (target 1,000 events/s) |
| Events produced, total | 89,900 | events | measured | prometheus: coobi_generator_events_total{result="published"} first to last sample |
| Share of the target sustained | 99.87 | % | measured | computed from the produced rate and the target of the scenario |
| Events received | 1,117.41 | events/s | measured | prometheus: logistics_events_received_total over the window |
| Events received, total | 100,585 | events | measured | prometheus: logistics_events_received_total first to last sample |
| Events processed | 1,117.41 | events/s | measured | prometheus: logistics_events_processed_total over the window |
| Events processed, total | 100,585 | events | measured | prometheus: logistics_events_processed_total first to last sample |
| Events failed | 0 | events/s | measured | prometheus: logistics_events_failed_total over the window |
| Events failed, total | 0 | events | measured | prometheus: logistics_events_failed_total first to last sample |
| Alerts generated | 0 | alerts | measured | prometheus: logistics_alerts_generated_total by type, first to last sample (SPEEDING plus VEHICLE_STOPPED) |
| Consumer lag, peak of the streams client metric | 2,719,740 | records | measured | prometheus: kafka_consumer_fetch_manager_records_lag_max, highest sample (per partition of the consumed topic, summed; the binder publishes it only while the streams are behind, so samples without it are not a lag of zero) |
| Consumer lag, broker group total | 2,858,851 | records | measured | kafka-consumer-groups.sh --describe, committed offset against the end of the log (the highest partition of the group was 502558 records behind, over 6 partitions, at the end of the window) |
| Processing latency p50 | 0.04 | ms | measured | prometheus: histogram_quantile over the window (one processing step of the topology, not the end-to-end age of an event) |
| Processing latency p95 | 1.39 | ms | measured | prometheus: histogram_quantile over the window (one processing step of the topology, not the end-to-end age of an event) |
| Processing latency p99 | 1.81 | ms | measured | prometheus: histogram_quantile over the window (one processing step of the topology, not the end-to-end age of an event) |
| Processing latency, mean | 0.42 | ms | measured | prometheus: change of the timer total over the change of its count |
| Processing latency, longest step | 91.14 | ms | measured | prometheus: logistics_event_processing_duration_seconds_max, highest sample |
| CPU of event-generator, mean | 3.40 | % of one core | measured | docker stats, averaged over the samples of the window (100% is one fully used core, a container may exceed it) |
| CPU of event-generator, p95 | 5.18 | % of one core | measured | docker stats, p95 across the samples of the window |
| CPU of event-generator, peak | 5.86 | % of one core | measured | docker stats, highest sample |
| Memory of event-generator, peak | 263.30 | MiB | measured | docker stats, highest resident memory of the window |
| CPU of stream-processor, mean | 81.73 | % of one core | measured | docker stats, averaged over the samples of the window (100% is one fully used core, a container may exceed it) |
| CPU of stream-processor, p95 | 215.11 | % of one core | measured | docker stats, p95 across the samples of the window |
| CPU of stream-processor, peak | 255.87 | % of one core | measured | docker stats, highest sample |
| Memory of stream-processor, peak | 692.80 | MiB | measured | docker stats, highest resident memory of the window |
| CPU of logistics-api, mean | 1.45 | % of one core | measured | docker stats, averaged over the samples of the window (100% is one fully used core, a container may exceed it) |
| CPU of logistics-api, p95 | 4.57 | % of one core | measured | docker stats, p95 across the samples of the window |
| CPU of logistics-api, peak | 5.06 | % of one core | measured | docker stats, highest sample |
| Memory of logistics-api, peak | 309.80 | MiB | measured | docker stats, highest resident memory of the window |
| CPU, highest container | 255.87 | % of one core | measured | docker stats, highest sample of any container of the run (container stream-processor) |
| Memory, highest container | 692.80 | MiB | measured | docker stats, highest resident memory of any container of the run (container stream-processor) |

## Observations of the run

| Observation | Measured | Held | Source |
| --- | --- | --- | --- |
| the pipeline invariant received = processed + failed | 100585 vs 100585 + 0 | yes | logistics_events_{received,processed,failed}_total in the samples |
| processing steps per accepted record | 3.000 | - | logistics_event_processing_duration_seconds_count / processed events |
| the target rate was sustained | 998.7 events/s of 1,000 | yes | coobi_generator_events_total{result="published"} over the window |
| the generator was still publishing at the end of the window | latest rate 1061.8 events/s | yes | the samples before the last one |
| every series the summary needs answered in every sample | 9 of 9 samples complete | yes | the raw samples of raw.jsonl |
| the measured window has the duration of the scenario | 90.1s of 90s | yes | wall clock of the harness between the first and the last sample |

## Environment of the run

- Host: 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logical CPUs, 31.8 GiB of memory, Windows-10-10.0.19045-SP0
- Docker: server 29.8.0, Compose 5.5.1, 16 CPUs and 15.5 GiB of memory visible to the engine
- Repository: a82c3043b760117d5c1c0a00a11eb9f9c25b5b3e (dirty)
- Prometheus scrape interval: 15s
- JVM of event-generator: 21.0.12, max heap 3.88G
- JVM of stream-processor: 21.0.12, max heap 3.88G
- JVM of logistics-api: 21.0.12, max heap 3.88G
- Kafka topic logistics.vehicle.location.v1: 6 partitions, replication factor 1

## Raw capture

`infrastructure/benchmarks/results/20260921T161334Z-1000-1000-90s/1000-1000-90s/raw.jsonl`
