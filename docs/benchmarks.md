---
layout: page
title: "Performance benchmarks"
description: "Reproducible throughput and latency measurements with their raw captures."
---

# Performance benchmarks

This document reports what the benchmark of this repository measured on the Dockerized stack of
`compose.yml`, how those numbers were obtained, and where the pipeline stops keeping up. Every
number below carries one of three labels, and no cell is ever a guess:

| Status | Meaning |
| --- | --- |
| `measured` | a value read from a source the stack exposes, captured in the files of the run |
| `unavailable` | the stack does not expose that source, so there is no value - and it is not a zero |
| `not run` | the scenario produced no value in the session that wrote this document |

The raw captures are kept next to the code (and committed with the phase that produced them), so
every number here can be traced back to a sample: `infrastructure/benchmarks/results/<run>/`.

## How to run a benchmark

```bash
# one scenario: <vehicles>/<target events per second>/<window>
python infrastructure/benchmarks/benchmark.py --scenario 10000/25000/5m

# the four standard scenarios, five minutes each
python infrastructure/benchmarks/benchmark.py --matrix

# the same four fleets and rates over a shorter window
python infrastructure/benchmarks/benchmark.py --matrix --duration 90s

# the harness' own tests, no Docker required
python -m unittest discover -s infrastructure/benchmarks/tests
```

The harness needs Python 3.11 or newer (the runs of this document used CPython 3.14) and the
Docker CLI with the Compose plugin. It starts and stops the stack itself.

| Option | Effect |
| --- | --- |
| `--scenario <v>/<r>/<d>` | one scenario, repeatable |
| `--matrix` | the four required scenarios: 1,000/1,000, 5,000/10,000, 10,000/25,000 and 20,000/50,000 |
| `--duration <d>` | the window of a `--matrix` run; the standard scenarios are five minutes (default) |
| `--sample-interval <d>` | how often the harness samples; `10s` by default |
| `--frontend` | include the dashboard; a benchmark leaves it out |
| `--reset-volumes` | delete the local volumes between scenarios; the default keeps them |
| `--no-build` | reuse the images instead of rebuilding them |
| `--startup-timeout`, `--target-timeout`, `--startup-allowance` | how long a run waits for a healthy stack, for the scrape targets and for the first samples |
| `--dry-run`, `--list` | print the plan of a run without starting or measuring anything |
| `--stop-stack` | stop the stack after the last scenario; the default leaves it up |

Each scenario writes `raw.jsonl` (every sample, one JSON object per line), `summary.json` (every
quantity with its status and its source) and `report.md` (that scenario). A `--matrix` invocation
also writes `matrix.md`, the table of the scenarios that produced a value.

## Methodology

One run of one scenario does this, in order:

1. **Validates the scenario** before touching the stack: `vehicles/target events per second/window`
   is parsed and rejected if it is malformed, and a matrix run is refused unless its duration is
   explicit.
2. **Stops whatever is running** with `docker compose down --remove-orphans`. The local volumes are
   kept, and `--reset-volumes` is the only thing that deletes them.
3. **Starts the stack** with the overlay `infrastructure/benchmarks/compose.benchmark.yml` on top of
   `compose.yml`, passing the fleet, the target rate and the window of the scenario in the
   environment of the Compose process (`BENCH_VEHICLES`, `BENCH_TARGET_EVENTS_PER_SECOND`,
   `BENCH_LOAD_TEST_DURATION`).
4. **Waits for the stack**: every healthcheck has to report `healthy`, and every Prometheus target
   has to be `up`, before the window opens.
5. **Samples the window** at `--sample-interval` from three sources - the Prometheus HTTP API, one
   `docker stats --no-stream` per sample, and the Kafka CLI inside the broker container.
6. **Summarises** the samples into the quantities of the scenario, checks the invariants it can
   check, and writes the three files of the run.

### Sources of the numbers

| Quantity | Source |
| --- | --- |
| Events produced, and the total | `coobi_generator_events_total{result="published"}`, Prometheus |
| Events received, processed, failed | `logistics_events_{received,processed,failed}_total`, Prometheus |
| Alerts generated | `logistics_alerts_generated_total`, Prometheus |
| Processing latency p50/p95/p99 | `histogram_quantile()` over `logistics_event_processing_duration_seconds_bucket` in the window, Prometheus |
| Processing latency mean and longest step | the timer's `_sum`, `_count` and `_max`, Prometheus |
| Consumer lag, client view | `kafka_consumer_fetch_manager_records_lag_max` of the processor, Prometheus |
| Consumer lag, broker view | `kafka-consumer-groups.sh --describe` inside the broker container |
| CPU and memory of a container | `docker stats --no-stream --format json`, one sample per interval |
| Partitions, replication factor, scrape interval, JVM | the broker CLI, the Prometheus configuration API and the JVM of each container |

The latency percentiles are percentiles of **one processing step of the topology** - validation plus
the two detections - and not of the age of an event between the generator and the API. The stack
exposes no producer-to-API age metric, so this document reports no end-to-end latency: that value is
`unavailable` by construction, not by omission. Percentiles of the latency come from the histogram
buckets of the `benchmark` Spring profile, because the default profile publishes only a count, a
total and a maximum, which give an average but no percentile. The profile adds a cumulative
histogram and an SLO grid down to 50 microseconds, and nothing else; it is requested by the overlay
and inert in a normal run.

CPU is expressed as a percentage of one core, so a container that uses three cores reads 300%. When
the component the harness samples is not a Prometheus query, the number is computed from the samples
it collected: the mean is the arithmetic mean of the samples of the window, the p95 is a
linear-interpolation percentile of those samples, and the peak is the highest sample.

### What the numbers are not

- Not a result for the 20,000/50,000 end of the matrix unless a row below says `measured`.
- Not end-to-end latency: the pipeline's latency is reported per processing step.
- Not a load-balancer or multi-node result: one host, one stack, every container sharing its CPUs.
- Not a distribution over many runs: each row is one window of one run, and the machine was also
  hosting the desktop session that drove the run.
- Not a comparison of configurations: `compose.yml` sets no CPU or memory limit, so the containers
  compete for the same 16 logical CPUs and the same 15.5 GiB of the Docker VM.

## Environment of the measurement

| Item | Value |
| --- | --- |
| Host CPU | 11th Gen Intel(R) Core(TM) i7-11800H @ 2.30GHz, 16 logical CPUs |
| Host memory | 31.8 GiB |
| Host operating system | Windows 10 (10.0.19045), Docker Desktop on WSL2 (`6.18.33.2-microsoft-standard-WSL2`) |
| Resources visible to Docker | 16 CPUs, 15.5 GiB |
| Docker server / Compose | 29.8.0 / 5.5.1 |
| Java | Eclipse Temurin 21.0.12 (measured in each of the three service containers) |
| JVM settings | `-XX:MaxRAMPercentage=75.0` and nothing else; the JVM resolves it to a maximum heap of 3.88 GiB per service on this host (measured) |
| Spring Boot | 3.5.16, Java 21 |
| Kafka | `apache/kafka:4.3.1`, client 3.9.2 |
| PostgreSQL | `postgres:18.6` |
| Prometheus | `prom/prometheus:v3.14.0-busybox`, scrape interval 15s |
| Repository revision | `a82c304` with the benchmark harness working tree |

### Partitions and topics

`logistics.vehicle.location.v1` carries the telemetry of the benchmark: **6 partitions,
replication factor 1, `min.insync.replicas=1`**, provisioned by the event generator's topic
initializer and read back from the broker by the harness. The partitions are what allow the
processor to run more than one stream task; they are also the ceiling of its parallelism.

### Configuration of the measured stack

| Setting | Value in a benchmark |
| --- | --- |
| Generator mode | `LOAD_TEST` (the overlay sets it; it is not the default of `compose.yml`) |
| Fleet and rate | the vehicles and the target events per second of the scenario |
| Generator window | the scenario window plus the startup allowance (`BENCH_LOAD_TEST_DURATION`) |
| Generator seed | `GENERATOR_RANDOM_SEED=20260101`, so every run replays the same fleet and the same movement |
| Stream processor profile | `benchmark` (cumulative histogram and SLO grid for the processing timer) |
| Dashboard | not started: the overlay puts it behind a profile and the harness does not request it |
| Local volumes | kept between scenarios; a scenario therefore inherits the Kafka log and the read model of the one before it |
| Speed and stop detection | `SPEED_LIMIT=120` km/h and `STOPPED_WINDOW_SECONDS=300`, the defaults |

## Results

### The four standard scenarios

All four were requested with `--matrix` (five minutes each, `--sample-interval 10s`), in the run
`infrastructure/benchmarks/results/20260921T160117Z-matrix/`:

| Scenario | Window | Events produced/s | Processed/s | Failed/s | p50 (ms) | p95 (ms) | p99 (ms) | Consumer lag, broker (records, end of window) | CPU peak (% of one core) | RAM peak (MiB) | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1,000 vehicles / 1,000 events/s | 5m | 1,000.00 | 1,134.02 | 0 | 0.04 | 1.41 | 1.84 | 902 | 329.40 (stream-processor) | 742.6 (stream-processor) | measured |
| 5,000 vehicles / 10,000 events/s | 5m | 9,999.97 | 1,483.51 | 0 | 0.04 | 1.37 | 1.72 | 2,857,784 | 245.76 (stream-processor) | 645.6 (stream-processor) | measured |
| 10,000 vehicles / 25,000 events/s | 5m | not run | not run | not run | not run | not run | not run | not run | not run | not run | not run |
| 20,000 vehicles / 50,000 events/s | 5m | not run | not run | not run | not run | not run | not run | not run | not run | not run | not run |

`unavailable` means the stack does not expose that measurement, and `not run` means the scenario did
not produce one. Neither is a zero. The two `not run` rows were stopped before their window closed,
so no summary exists for them and no number here describes them; the command that completes them is
`python infrastructure/benchmarks/benchmark.py --matrix`.

| Scenario | Left to the pipeline by its own window | Produced, total | Processed, total | Observations that held |
| --- | --- | --- | --- | --- |
| 1,000 / 1,000 | -40,200 records | 300,000 | 340,200 | target sustained, received = processed + failed, generator still publishing at the end, 26 of 26 samples complete |
| 5,000 / 10,000 | 2,554,947 records | 3,000,000 | 445,053 | target sustained, received = processed + failed, generator still publishing at the end, 26 of 26 samples complete |

"Left to the pipeline by its own window" is the produced total minus the processed total: what the
scenario offered and the pipeline had not processed when the window closed. It is derived from two
captured counters, not sampled separately, and it is negative when the window consumed more than it
produced - which is what draining the backlog of earlier runs looks like.

Two readings of the 1,000/1,000 row need their context stated, because the harness keeps the local
volumes:

- `Processed/s` (1,134.02) is **higher** than `produced/s` (1,000.00) because the consumer group
  drained records that earlier runs had left in the topic. The produced rate is what the scenario
  targets and the generator sustained; the processed rate is what the pipeline managed while it
  also worked through that backlog.
- `Consumer lag, broker` is the broker's own count of uncommitted records for the processor's group
  at the end of the window, and it therefore includes what the previous run left behind. On a
  cleared topic (`--reset-volumes`) it is the backlog of the scenario alone.

The client-side lag of the Kafka Streams binder is reported separately in each `summary.json`
(`Consumer lag, peak of the streams client metric`: 47,139 records for the first scenario, 2,235,158
for the second, over the partitions of the consumed topic). The binder publishes that series only
while the streams are behind, so a sample without it is not a lag of zero, and the broker's count is
the authoritative one.

Per scenario, the captures are:

| Scenario | Raw samples | Summary | Report |
| --- | --- | --- | --- |
| 1,000 / 1,000, 5m | `results/20260921T160117Z-matrix/1000-1000-5m/raw.jsonl` | `summary.json` | `report.md` |
| 5,000 / 10,000, 5m | `results/20260921T160117Z-matrix/5000-10000-5m/raw.jsonl` | `summary.json` | `report.md` |

### Bounded smoke run

A short run of the same harness over the same stack, to show that a window that is not five minutes
long is measured the same way (`results/20260921T161334Z-1000-1000-90s/`):

| Scenario | Window | Samples | Events produced/s | Processed/s | Failed/s | p50 (ms) | p95 (ms) | p99 (ms) | Consumer lag, broker | CPU peak | RAM peak |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1,000 / 1,000, 90s | 90s | 9 every 10s | 998.72 | 1,117.41 | 0 | 0.04 | 1.39 | 1.81 | 2,858,851 records | 255.87% (stream-processor) | 692.8 MiB (stream-processor) |

Every quantity of that run is `measured`; no row of its summary is `unavailable`. Its consumer lag
is the backlog the stopped matrix scenarios had left in the topic, which is exactly what the broker
reports when the volumes are kept.

## Bottlenecks and observations

1. **The stack does not sustain 10,000 events/s.** The second scenario offered 10,000 events/s for
   five minutes - and the generator sustained its target (9,999.97 events/s, 100.0% of it, still
   publishing at the end) - while the pipeline processed 1,483.51 events/s. Within that single
   window, 2,554,947 records were left unprocessed, and the broker's group lag ended at 2,857,784
   records. The first scenario, at a tenth of that rate, kept up.
2. **The ceiling is not CPU-bound on the processor.** The stream-processor's CPU peaked at 329.40%
   of one core in the 1,000/1,000 scenario and at 245.76% in the 5,000/10,000 one - 21% and 15% of
   the 16 logical CPUs of the host, and lower at the higher rate, where the backlog grew fastest.
   The busiest container at 10,000 events/s was still the processor, but it was not saturated, so
   the limit of this deployment is not the CPU of the topology. Locating it precisely would need
   profiling of the processor - the persistence step, the state store commits and the Kafka client
   settings are the candidates - which this benchmark does not do.
3. **Latency is not the constraint.** The processing step stayed fast at both rates: p50 0.04 ms,
   p95 1.41 ms, p99 1.84 ms in the first scenario and p95 1.37 ms, p99 1.72 ms in the second, with
   the longest single step at 132.19 ms and 123.28 ms. The pipeline falls behind by *not doing more
   of the same work*, not by doing each unit of it slowly, which is consistent with a throughput
   limit rather than a latency one.
4. **Failures stay at zero.** No scenario processed a failed record (0 events/s, and
   received = processed + failed in every sample), so the backlog is not the result of error
   handling.
5. **Memory is not the constraint either.** The peak resident memory of the busiest container was
   742.6 MiB (stream-processor, 1,000/1,000) and 645.6 MiB (5,000/10,000), against a 15.5 GiB Docker
   VM and a 3.88 GiB maximum heap per service.
6. **The generator is cheap at both rates.** Its CPU peaked at 33.43% of one core at 1,000 events/s
   and 13.88% at 10,000 events/s, and the API stayed under 7% of one core in both windows, so the
   benchmark measures the processor rather than its producers.
7. **A kept topic contaminates `processed/s` and the lag of the next scenario.** Because the
   volumes are kept, the first scenario's processed rate includes the backlog of earlier runs. The
   comparison between scenarios is still meaningful for the offered rate and for the backlog the
   window leaves, and it is not a clean-slate throughput comparison: use `--reset-volumes` when
   that is what a run needs.

## Limitations

| Limitation | Consequence |
| --- | --- |
| The matrix was stopped after the second scenario of this session | The 10,000/25,000 and 20,000/50,000 scenarios are `not run`; no throughput, latency or resource value exists for them here |
| No end-to-end latency metric exists in the stack | Only the processing step is reported; the age of an event between producer and API is `unavailable` |
| Prometheus scrapes every 15s and the harness samples every 10s | Bursts shorter than a scrape interval are averaged into it, and a peak between two samples is not seen |
| `docker stats --no-stream` is a point-in-time sample per interval | CPU and memory are sampled, not integrated; the peak is the highest sample, not the true peak |
| No CPU or memory limit is set in `compose.yml` | The containers share the host; a quieter or busier desktop changes what each one gets |
| One run per scenario | These are single windows, not distributions over repeated runs |
| The latency histogram comes from the `benchmark` profile | Percentile accuracy is bounded by the SLO bucket grid of that profile (50 microseconds to 1 second) |

## Reproducing this document

```bash
# the two measured scenarios, five minutes each (about 25 minutes of wall clock)
python infrastructure/benchmarks/benchmark.py --matrix

# the bounded smoke run
python infrastructure/benchmarks/benchmark.py --scenario 1000/1000/90s

# the harness' unit tests
python -m unittest discover -s infrastructure/benchmarks/tests
```

The harness needs the stack it measures to be free to build and start: it stops the Compose project
of this repository, rebuilds the images unless `--no-build` is passed, and leaves the stack running
unless `--stop-stack` is passed. It never deletes a volume unless `--reset-volumes` is passed.
