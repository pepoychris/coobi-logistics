# Benchmark scenario 1000-1000-5m

| Scenario | Value |
| --- | --- |
| Vehicles | 1,000 |
| Target events per second | 1,000 |
| Measured window | 5m (300.07800006866455 seconds of wall clock) |
| Samples | 26 every 10s |
| Started (UTC) | 2026-09-21T16:02:09Z |
| Finished (UTC) | 2026-09-21T16:07:09Z |
| Local volumes reset | no |
| Dashboard in the run | no |

## Results

| Metric | Value | Unit | Status | Source |
| --- | --- | --- | --- | --- |
| Events produced | 1,000 | events/s | measured | prometheus: coobi_generator_events_total{result="published"} over the window (target 1,000 events/s) |
| Events produced, total | 300,000 | events | measured | prometheus: coobi_generator_events_total{result="published"} first to last sample |
| Share of the target sustained | 100 | % | measured | computed from the produced rate and the target of the scenario |
| Events received | 1,134.01 | events/s | measured | prometheus: logistics_events_received_total over the window |
| Events received, total | 340,200 | events | measured | prometheus: logistics_events_received_total first to last sample |
| Events processed | 1,134.02 | events/s | measured | prometheus: logistics_events_processed_total over the window |
| Events processed, total | 340,200 | events | measured | prometheus: logistics_events_processed_total first to last sample |
| Events failed | 0 | events/s | measured | prometheus: logistics_events_failed_total over the window |
| Events failed, total | 0 | events | measured | prometheus: logistics_events_failed_total first to last sample |
| Alerts generated | 0 | alerts | measured | prometheus: logistics_alerts_generated_total by type, first to last sample (SPEEDING plus VEHICLE_STOPPED) |
| Consumer lag, peak of the streams client metric | 47,139 | records | measured | prometheus: kafka_consumer_fetch_manager_records_lag_max, highest sample (per partition of the consumed topic, summed; the binder publishes it only while the streams are behind, so samples without it are not a lag of zero) |
| Consumer lag, broker group total | 902 | records | measured | kafka-consumer-groups.sh --describe, committed offset against the end of the log (the highest partition of the group was 195 records behind, over 6 partitions, at the end of the window) |
| Processing latency p50 | 0.04 | ms | measured | prometheus: histogram_quantile over the window (one processing step of the topology, not the end-to-end age of an event) |
| Processing latency p95 | 1.41 | ms | measured | prometheus: histogram_quantile over the window (one processing step of the topology, not the end-to-end age of an event) |
| Processing latency p99 | 1.84 | ms | measured | prometheus: histogram_quantile over the window (one processing step of the topology, not the end-to-end age of an event) |
| Processing latency, mean | 0.41 | ms | measured | prometheus: change of the timer total over the change of its count |
| Processing latency, longest step | 132.19 | ms | measured | prometheus: logistics_event_processing_duration_seconds_max, highest sample |
| CPU of event-generator, mean | 4.87 | % of one core | measured | docker stats, averaged over the samples of the window (100% is one fully used core, a container may exceed it) |
| CPU of event-generator, p95 | 15.20 | % of one core | measured | docker stats, p95 across the samples of the window |
| CPU of event-generator, peak | 33.43 | % of one core | measured | docker stats, highest sample |
| Memory of event-generator, peak | 261.40 | MiB | measured | docker stats, highest resident memory of the window |
| CPU of stream-processor, mean | 55.41 | % of one core | measured | docker stats, averaged over the samples of the window (100% is one fully used core, a container may exceed it) |
| CPU of stream-processor, p95 | 78 | % of one core | measured | docker stats, p95 across the samples of the window |
| CPU of stream-processor, peak | 329.40 | % of one core | measured | docker stats, highest sample |
| Memory of stream-processor, peak | 742.60 | MiB | measured | docker stats, highest resident memory of the window |
| CPU of logistics-api, mean | 0.45 | % of one core | measured | docker stats, averaged over the samples of the window (100% is one fully used core, a container may exceed it) |
| CPU of logistics-api, p95 | 0.30 | % of one core | measured | docker stats, p95 across the samples of the window |
| CPU of logistics-api, peak | 6.57 | % of one core | measured | docker stats, highest sample |
| Memory of logistics-api, peak | 312.90 | MiB | measured | docker stats, highest resident memory of the window |
| CPU, highest container | 329.40 | % of one core | measured | docker stats, highest sample of any container of the run (container stream-processor) |
| Memory, highest container | 742.60 | MiB | measured | docker stats, highest resident memory of any container of the run (container stream-processor) |

## Observations of the run

| Observation | Measured | Held | Source |
| --- | --- | --- | --- |
| the pipeline invariant received = processed + failed | 340200 vs 340200 + 0 | yes | logistics_events_{received,processed,failed}_total in the samples |
| processing steps per accepted record | 3.000 | - | logistics_event_processing_duration_seconds_count / processed events |
| the target rate was sustained | 1000.0 events/s of 1,000 | yes | coobi_generator_events_total{result="published"} over the window |
| the generator was still publishing at the end of the window | latest rate 990.0 events/s | yes | the samples before the last one |
| every series the summary needs answered in every sample | 26 of 26 samples complete | yes | the raw samples of raw.jsonl |
| the measured window has the duration of the scenario | 300.1s of 300s | yes | wall clock of the harness between the first and the last sample |

## Environment of the run

- Host: 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logical CPUs, 31.8 GiB of memory, Windows-10-10.0.19045-SP0
- Docker: server 29.8.0, Compose 5.5.1, 16 CPUs and 15.5 GiB of memory visible to the engine
- Repository: a82c3043b760117d5c1c0a00a11eb9f9c25b5b3e (dirty)
- Prometheus scrape interval: 15s
- JVM of event-generator: 21.0.12, max heap None
- JVM of stream-processor: 21.0.12, max heap None
- JVM of logistics-api: 21.0.12, max heap None
- Kafka topic logistics.vehicle.location.v1: 6 partitions, replication factor 1

## Raw capture

`infrastructure/benchmarks/results/20260921T160117Z-matrix/1000-1000-5m/raw.jsonl`
