#!/usr/bin/env python3
"""MVP-11 benchmark harness for the Dockerized Coobi Logistics stack.

One command runs one scenario, or the complete matrix of MVP-11.2, against the Compose stack
of this repository and writes what it measured to ``infrastructure/benchmarks/results``:

    python infrastructure/benchmarks/benchmark.py --scenario 1000/1000/5m
    python infrastructure/benchmarks/benchmark.py --matrix

What a run does, in order:

1. stops whatever is running (``docker compose down --remove-orphans``), so the scenario starts
   from a stack it knows the state of. The local volumes are kept: `--reset-volumes` deletes them
   as well, at the price of the telemetry a local stack had accumulated;
2. starts the pipeline services with the benchmark overlay, which drives the generator in
   ``LOAD_TEST`` mode at the fleet and the rate of the scenario, bounds the load with
   ``LOAD_TEST_DURATION`` and turns on the latency distribution of the stream processor;
3. waits for every service to report ``healthy`` and for both Prometheus targets to be ``up``;
4. samples the stack for the duration of the scenario - the Prometheus counters, timers and
   consumer lag of the two services, and the CPU and the memory of every container - writing
   each sample to ``raw.jsonl`` as it is taken;
5. stops the generator so the load ends with the measured window, and computes the summary
   from the samples and from one final Prometheus query per derived metric (the percentiles of
   the processing latency, for instance, over exactly the window that was measured);
6. writes ``summary.json`` and ``report.md`` next to the raw samples.

Nothing is estimated and nothing is filled in: a quantity whose source is not exposed by this
stack is written as ``unavailable``, and a scenario that was not run is written as ``not run``.

Requires Python 3.11 or newer and the Docker CLI with the Compose plugin. The harness itself
needs no third-party package.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import platform
import re
import shutil
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import metrics as bench_metrics  # noqa: E402  (the harness is a directory of scripts)
import scenarios as bench_scenarios  # noqa: E402
from scenarios import MEASURED, NOT_RUN, UNAVAILABLE, Quantity, Scenario  # noqa: E402
from scenarios import BenchmarkInputError  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = "compose.yml"
BENCHMARK_COMPOSE_FILE = "infrastructure/benchmarks/compose.benchmark.yml"
RESULTS_ROOT = REPO_ROOT / "infrastructure" / "benchmarks" / "results"

PIPELINE_SERVICES = (
    "kafka",
    "postgres",
    "prometheus",
    "event-generator",
    "stream-processor",
    "logistics-api",
)
FRONTEND_SERVICE = "frontend"
FRONTEND_PROFILE = "frontend"

# The services a benchmark samples, either from Prometheus or from `docker stats`.
CONTAINER_SERVICES = PIPELINE_SERVICES[3:] + (FRONTEND_SERVICE,)

#
# The loopback address, spelled as an address and not as `localhost`: on a Windows host with an
# IPv6 stack, `localhost` resolves to `::1` first, the published port of the Prometheus container
# answers on IPv4 only, and every query then pays the failed attempt - two seconds per query,
# measured on the machine this harness was written on, against seven milliseconds for the same
# query on `127.0.0.1`.
#
DEFAULT_PROMETHEUS_URL = "http://127.0.0.1:9090"
DEFAULT_SAMPLE_INTERVAL_SECONDS = 10.0
DEFAULT_STARTUP_TIMEOUT_SECONDS = 900.0
#
# A bounded load test is stopped by the harness at the end of the measured window, so the
# duration the generator is given is longer than the window: it is the safety net that stops the
# load if the harness itself dies, not the end of the measurement.
#
DEFAULT_STARTUP_ALLOWANCE_SECONDS = 180.0

# Metrics the summary cannot be computed without. A sample that misses one of them is an
# incomplete sample, and the run says so; a metric that is merely absent on this stack (consumer
# lag on an idle streams application, for instance) is not.
REQUIRED_SAMPLE_METRICS = (
    "generator_published_total",
    "received_total",
    "processed_total",
    "failed_total",
    "processing_duration_count",
    "processing_duration_sum",
)

LOCATION_TOPIC = "logistics.vehicle.location.v1"

# The group Kafka Streams commits its offsets under: the application id of the processor, which
# is what `kafka-consumer-groups.sh --describe --group <this>` lists.
STREAMS_APPLICATION_ID = "coobi-stream-processor"

# Metrics read once per sample. Every query is stated here rather than inline, so the report can
# name the source of a number and a reader can run the same query by hand.
SAMPLE_QUERIES: dict[str, str] = {
    "generator_published_total": 'sum(coobi_generator_events_total{result="published"})',
    "generator_failed_total": 'sum(coobi_generator_events_total{result="failed"})',
    "generator_send_rate": "sum(rate(kafka_producer_record_send_total[1m]))",
    "received_total": "sum(logistics_events_received_total)",
    "processed_total": "sum(logistics_events_processed_total)",
    "failed_total": "sum(logistics_events_failed_total)",
    "alerts_speeding_total": 'sum(logistics_alerts_generated_total{type="SPEEDING"})',
    "alerts_stopped_total": 'sum(logistics_alerts_generated_total{type="VEHICLE_STOPPED"})',
    "processing_duration_count": "sum(logistics_event_processing_duration_seconds_count)",
    "processing_duration_sum": "sum(logistics_event_processing_duration_seconds_sum)",
    "processing_duration_max": "max(logistics_event_processing_duration_seconds_max)",
    # Scoped to the topic this pipeline consumes, and to the partitions of it: the metric also
    # exists for the restore consumers of the state-store changelog topics, which are not the
    # backlog a reader means by "consumer lag".
    "consumer_lag_max": (
        'sum(kafka_consumer_fetch_manager_records_lag_max{topic="logistics_vehicle_location_v1"})'
    ),
    "stream_thread_process_total": "sum(kafka_stream_thread_process_total)",
    "stream_thread_process_rate": "sum(kafka_stream_thread_process_rate)",
    "producer_error_rate": "sum(rate(kafka_producer_record_error_rate[1m]))",
}

# The percentiles Micrometer publishes as gauges of the timer when a stack is configured that
# way. The Prometheus registry of this repository exports the timer as a histogram instead, so
# these are read only at the end of a window and only as a fallback: `docs/benchmarks.md` reports
# the histogram, and a stack without it still gets a percentile when the gauges are there.
GAUGE_QUERIES: dict[str, str] = {
    "latency_p50_gauge": 'avg(logistics_event_processing_duration_seconds{quantile="0.5"})',
    "latency_p95_gauge": 'avg(logistics_event_processing_duration_seconds{quantile="0.95"})',
    "latency_p99_gauge": 'avg(logistics_event_processing_duration_seconds{quantile="0.99"})',
}


def window_queries(window_seconds: float) -> dict[str, str]:
    """The derived queries of a window: percentiles and averages over exactly that window.

    ``histogram_quantile`` needs a rate over the window, and the boundary of the selector is the
    window of the scenario, so the percentile is computed from every observation of the run
    rather than from the rolling window of a gauge.
    """
    window = f"{window_seconds:g}s"
    quantiles = {}
    for name, quantile in (("p50", 0.5), ("p95", 0.95), ("p99", 0.99)):
        quantiles[f"latency_{name}_histogram"] = (
            f"histogram_quantile({quantile}, "
            f"sum(rate(logistics_event_processing_duration_seconds_bucket[{window}])) by (le))"
        )
        quantiles[f"latency_{name}_gauge_average"] = (
            f"avg_over_time(({GAUGE_QUERIES[f'latency_{name}_gauge']})[{window}:15s])"
        )
    quantiles["processing_duration_seconds_histogram"] = (
        f"sum(rate(logistics_event_processing_duration_seconds_sum[{window}])) / "
        f"sum(rate(logistics_event_processing_duration_seconds_count[{window}]))"
    )
    quantiles["generator_send_rate_window"] = (
        f"sum(rate(kafka_producer_record_send_total[{window}]))"
    )
    return quantiles


class BenchmarkError(RuntimeError):
    """A run that cannot be completed as requested."""


def log(message: str) -> None:
    """One line of progress, on stderr so stdout stays a machine-readable plan."""
    print(f"[{datetime.now(timezone.utc).strftime('%H:%M:%S')}] {message}", file=sys.stderr, flush=True)


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def run_directory_name() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def run_command(
    args: list[str], cwd: Path | None = None, env: dict[str, str] | None = None
) -> subprocess.CompletedProcess:
    """Run one command and capture it, decoding whatever the tool prints."""
    return subprocess.run(  # noqa: S603 - the commands are the harness' own, not user input
        args,
        cwd=str(cwd or REPO_ROOT),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )


def service_of_container(payload: dict, by_id: dict[str, str]) -> str:
    """The compose service a `docker stats` entry describes.

    ``by_id`` maps the full container ids of the run to the service that owns each one, and it is
    the only thing this function reads besides the entry itself.

    ``docker stats --format json`` answers with the short id of a container, while the ids this
    run collected with ``docker compose ps -q`` are the full ones, so an entry is matched on the
    prefix the two share. An entry that matches nothing keeps the name the engine reported: it is
    still a real measurement of a container, and naming that container is more use than dropping
    the row - the per-service rows of the summary then say they have no measurement of their own
    rather than a number taken from somewhere else.
    """
    identifier = str(payload.get("ID") or "").strip()
    if identifier:
        exact = by_id.get(identifier)
        if exact:
            return exact
        for full, service in by_id.items():
            if full.startswith(identifier) or identifier.startswith(full):
                return service
    return str(payload.get("Name") or payload.get("Container") or "unknown")


class Prometheus:
    """The API of the Prometheus of the stack, and the targets it scrapes."""

    def __init__(self, base_url: str, timeout: float = 15.0) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def healthy(self) -> bool:
        try:
            with urllib.request.urlopen(f"{self.base_url}/-/healthy", timeout=self.timeout):
                return True
        except (urllib.error.URLError, OSError):
            return False

    def query(self, expression: str) -> list[dict]:
        """One instant query, as a list of ``{"metric": {...}, "value": float, "timestamp": float}``."""
        url = f"{self.base_url}/api/v1/query?{urllib.parse.urlencode({'query': expression})}"
        with urllib.request.urlopen(url, timeout=self.timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
        if payload.get("status") != "success":
            raise BenchmarkError(f"prometheus answered '{payload.get('error')}' for {expression}")
        result = payload["data"]["result"]
        samples = []
        for entry in result:
            try:
                value = float(entry["value"][1])
            except (KeyError, TypeError, ValueError):
                continue
            samples.append(
                {
                    "metric": entry.get("metric", {}),
                    "value": value,
                    "timestamp": float(entry["value"][0]),
                }
            )
        return samples

    def scalar(self, expression: str) -> tuple[float, float] | None:
        """The single value of a query, or ``None`` when the series does not exist."""
        samples = self.query(expression)
        if len(samples) != 1:
            return None
        if samples[0]["value"] != samples[0]["value"]:  # NaN
            return None
        return samples[0]["value"], samples[0]["timestamp"]

    def targets(self) -> dict[str, str]:
        """Health of every active scrape target, keyed by job."""
        url = f"{self.base_url}/api/v1/targets"
        with urllib.request.urlopen(url, timeout=self.timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
        health = {}
        for target in payload.get("data", {}).get("activeTargets", []):
            job = target.get("labels", {}).get("job")
            if job:
                health[job] = target.get("health", "unknown")
        return health


class Docker:
    """The Docker CLI and the Compose stack of this repository."""

    def __init__(self, compose_files: list[str], environment: dict[str, str]) -> None:
        self.compose_files = compose_files
        self.environment = environment

    def compose_command(self, args: list[str], global_options: list[str] | None = None) -> list[str]:
        command = ["docker", "compose"]
        for compose_file in self.compose_files:
            command += ["-f", compose_file]
        command += global_options or []
        return command + args

    def compose(
        self,
        args: list[str],
        global_options: list[str] | None = None,
        env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess:
        return run_command(
            self.compose_command(args, global_options),
            env={**self.environment, **(env or {})},
        )

    def container_id(self, service: str) -> str | None:
        result = self.compose(["ps", "-q", service])
        identifier = result.stdout.strip().splitlines()
        return identifier[0].strip() if identifier and identifier[0].strip() else None

    def container_ids(self, services: tuple[str, ...] | list[str]) -> dict[str, str]:
        found = {}
        for service in services:
            identifier = self.container_id(service)
            if identifier:
                found[service] = identifier
        return found

    def inspect(self, container: str, template: str) -> str | None:
        result = run_command(["docker", "inspect", "--format", template, container])
        if result.returncode != 0:
            return None
        return result.stdout.strip()

    def health(self, service: str) -> str:
        """``healthy``, ``starting``, ``unhealthy``, ``running`` or ``missing``."""
        identifier = self.container_id(service)
        if not identifier:
            return "missing"
        status = self.inspect(
            identifier,
            "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
        )
        return (status or "unknown").strip()

    def stats(self, containers: dict[str, str]) -> dict[str, dict]:
        """CPU and memory of every container, in one `docker stats` sample."""
        if not containers:
            return {}
        result = run_command(
            ["docker", "stats", "--no-stream", "--format", "json", *containers.values()]
        )
        if result.returncode != 0:
            return {}
        by_id = {identifier: service for service, identifier in containers.items()}
        samples = {}
        for line in result.stdout.splitlines():
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            parsed = bench_metrics.parse_docker_stats(payload)
            if parsed is None:
                continue
            parsed["service"] = service_of_container(payload, by_id)
            samples[parsed["service"]] = parsed
        return samples

    def exec(self, service: str, command: list[str]) -> str | None:
        result = self.compose(["exec", "-T", service, *command])
        if result.returncode != 0:
            return None
        # Both streams: `kafka-topics.sh` answers on stdout, and the JVM prints its settings and
        # its own version on stderr, so reading one stream would report the JVM of a container
        # as unknown while the command that describes it succeeded.
        return result.stdout + result.stderr

    def info(self) -> dict:
        result = run_command(["docker", "info", "--format", "{{json .}}"])
        if result.returncode != 0:
            return {}
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError:
            return {}

    def server_version(self) -> str | None:
        result = run_command(["docker", "version", "--format", "{{.Server.Version}}"])
        if result.returncode != 0:
            return None
        return result.stdout.strip() or None

    def client_version(self) -> str | None:
        result = run_command(["docker", "compose", "version", "--short"])
        if result.returncode != 0:
            return None
        return result.stdout.strip() or None


@dataclass
class RunContext:
    """Everything a scenario needs to be started, sampled and reported."""

    scenario: Scenario
    argument: argparse.Namespace
    docker: Docker
    prometheus: Prometheus
    run_dir: Path
    compose_environment: dict[str, str]
    services: tuple[str, ...]
    global_options: list[str]


def wait_for_health(docker: Docker, services: tuple[str, ...], timeout: float) -> dict[str, str]:
    """Poll until every service is healthy, and report the last state of each one."""
    deadline = time.monotonic() + timeout
    health = {service: "missing" for service in services}
    while True:
        health = {service: docker.health(service) for service in services}
        pending = {service: state for service, state in health.items() if state != "healthy"}
        if not pending:
            return health
        if time.monotonic() >= deadline:
            raise BenchmarkError(
                "the stack did not become healthy within "
                f"{timeout:g}s: {', '.join(f'{service}={state}' for service, state in pending.items())}"
            )
        log(
            "waiting for the stack: "
            + ", ".join(f"{service}={state}" for service, state in pending.items())
        )
        time.sleep(5)


def wait_for_targets(prometheus: Prometheus, timeout: float) -> dict[str, str]:
    """Poll until Prometheus scrapes both services, so no sample of the window is missing."""
    deadline = time.monotonic() + timeout
    health: dict[str, str] = {}
    while True:
        try:
            health = prometheus.targets()
        except (urllib.error.URLError, OSError, json.JSONDecodeError) as unavailable:
            log(f"prometheus is not answering yet ({unavailable})")
            health = {}
        down = {
            job: state
            for job, state in health.items()
            if job in ("event-generator", "stream-processor") and state != "up"
        }
        if health.get("event-generator") == "up" and health.get("stream-processor") == "up":
            return health
        if time.monotonic() >= deadline:
            raise BenchmarkError(
                "prometheus did not report both scrape targets as up within "
                f"{timeout:g}s: {down or health or 'no target'}"
            )
        time.sleep(5)


def take_sample(context: RunContext, queries: dict[str, str], containers: dict[str, str]) -> dict:
    """One sample of the window: the Prometheus series and the container resources."""
    started = time.monotonic()
    sample: dict = {"t_utc": utc_now(), "metrics": {}, "containers": {}}
    for name, expression in queries.items():
        try:
            values = context.prometheus.query(expression)
        except Exception as failure:  # noqa: BLE001 - a source that did not answer, whatever it was
            # A query that fails is a series this sample does not have, and a five-minute run is
            # not thrown away for one of them: the failure is written next to the series, the
            # summary states the series as unavailable, and the run reports how many samples
            # arrived complete.
            sample["metrics"][name] = {"value": None, "error": str(failure), "query": expression}
            continue
        if len(values) == 1:
            value = values[0]["value"]
            if not math.isfinite(value):
                # Prometheus answers with NaN when it cannot evaluate an expression - a rate over
                # a window it has no samples for, for instance. That is the absence of a
                # measurement, not a measurement of zero, and the report says so.
                sample["metrics"][name] = {
                    "value": None,
                    "query": expression,
                    "note": f"prometheus answered {value}",
                }
            else:
                sample["metrics"][name] = {
                    "value": value,
                    "timestamp": values[0]["timestamp"],
                    "query": expression,
                }
        elif not values:
            sample["metrics"][name] = {"value": None, "query": expression, "note": "no series"}
        else:
            finite = [entry for entry in values if math.isfinite(entry["value"])]
            if not finite:
                sample["metrics"][name] = {
                    "value": None,
                    "query": expression,
                    "note": f"{len(values)} series, none of them a number",
                }
                continue
            total = sum(entry["value"] for entry in finite)
            sample["metrics"][name] = {
                "value": total,
                "timestamp": max(entry["timestamp"] for entry in finite),
                "query": expression,
                "note": f"{len(finite)} of {len(values)} series, summed",
            }
    sample["containers"] = context.docker.stats(containers)
    sample["duration_ms"] = round((time.monotonic() - started) * 1000, 1)
    return sample


def series(samples: list[dict], name: str) -> list[tuple[float, float]]:
    """The ``(timestamp, value)`` points of one metric across the samples of a window."""
    points = []
    for sample in samples:
        entry = sample.get("metrics", {}).get(name)
        if entry is None or entry.get("value") is None:
            continue
        points.append((entry.get("timestamp", 0.0), entry["value"]))
    return points


def container_series(samples: list[dict], service: str, field: str) -> list[float]:
    """One resource of one container across the samples of a window."""
    values = []
    for sample in samples:
        entry = sample.get("containers", {}).get(service)
        if entry is None or entry.get(field) is None:
            continue
        values.append(float(entry[field]))
    return values


def _value(final: dict[str, float | None], name: str) -> float | None:
    return final.get(name)


def summarize(
    scenario: Scenario,
    samples: list[dict],
    final: dict[str, float | None],
    window_seconds: float,
    containers: dict[str, str],
    environment: dict,
    broker_lag: dict | None = None,
) -> dict:
    """The quantities of one scenario, each of them labelled with the source it came from."""
    quantities: list[Quantity] = []

    def add(quantity: Quantity) -> None:
        quantities.append(quantity)

    add(
        bench_scenarios.measured(
            "Events produced",
            "events/s",
            bench_metrics.sustained_rate(series(samples, "generator_published_total")),
            "prometheus: coobi_generator_events_total{result=\"published\"} over the window",
            f"target {scenario.target_events_per_second:,} events/s",
        )
    )
    add(
        bench_scenarios.measured(
            "Events produced, total",
            "events",
            bench_metrics.counter_delta(series(samples, "generator_published_total")),
            "prometheus: coobi_generator_events_total{result=\"published\"} first to last sample",
        )
    )
    produced = bench_metrics.sustained_rate(series(samples, "generator_published_total"))
    add(
        bench_scenarios.measured(
            "Share of the target sustained",
            "%",
            None if produced is None else produced / scenario.target_events_per_second * 100.0,
            "computed from the produced rate and the target of the scenario",
        )
    )
    for metric, name, source in (
        ("received_total", "Events received", 'logistics_events_received_total'),
        ("processed_total", "Events processed", "logistics_events_processed_total"),
        ("failed_total", "Events failed", "logistics_events_failed_total"),
    ):
        add(
            bench_scenarios.measured(
                f"{name}",
                "events/s",
                bench_metrics.sustained_rate(series(samples, metric)),
                f"prometheus: {source} over the window",
            )
        )
        add(
            bench_scenarios.measured(
                f"{name}, total",
                "events",
                bench_metrics.counter_delta(series(samples, metric)),
                f"prometheus: {source} first to last sample",
            )
        )
    add(
        bench_scenarios.measured(
            "Alerts generated",
            "alerts",
            _sum_or_none(
                bench_metrics.counter_delta(series(samples, "alerts_speeding_total")),
                bench_metrics.counter_delta(series(samples, "alerts_stopped_total")),
            ),
            "prometheus: logistics_alerts_generated_total by type, first to last sample",
            "SPEEDING plus VEHICLE_STOPPED",
        )
    )
    add(
        bench_scenarios.measured(
            "Consumer lag, peak of the streams client metric",
            "records",
            _max_or_none(series(samples, "consumer_lag_max")),
            "prometheus: kafka_consumer_fetch_manager_records_lag_max, highest sample",
            "per partition of the consumed topic, summed; the binder publishes it only while the "
            "streams are behind, so samples without it are not a lag of zero",
        )
    )
    broker = broker_lag or {"status": UNAVAILABLE, "source": "kafka-consumer-groups.sh"}
    add(
        bench_scenarios.measured(
            "Consumer lag, broker group total",
            "records",
            broker.get("records"),
            broker.get("source", "kafka-consumer-groups.sh"),
            broker.get("reason")
            or (
                f"the highest partition of the group was {broker.get('highestPartitionRecords')} "
                f"records behind, over {broker.get('partitions')} partitions, at the end of the window"
            ),
        )
    )

    histogram_present = final.get("latency_p95_histogram") is not None
    gauge_present = final.get("latency_p95_gauge_average") is not None
    for name in ("p50", "p95", "p99"):
        value = _value(final, f"latency_{name}_histogram")
        source = "prometheus: histogram_quantile over the window"
        note = ""
        if value is None:
            value = _value(final, f"latency_{name}_gauge_average")
            source = "prometheus: micrometer quantile gauge, averaged over the window"
            note = "the cumulative histogram of the timer was not published"
        add(
            bench_scenarios.measured(
                f"Processing latency {name}",
                "ms",
                None if value is None else value * 1000.0,
                source,
                note
                or (
                    "one processing step of the topology, not the end-to-end age of an event"
                ),
            )
        )
    add(
        bench_scenarios.measured(
            "Processing latency, mean",
            "ms",
            _mean_step_millis(samples),
            "prometheus: change of the timer total over the change of its count",
        )
    )
    add(
        bench_scenarios.measured(
            "Processing latency, longest step",
            "ms",
            _max_or_none(series(samples, "processing_duration_max"), factor=1000.0),
            "prometheus: logistics_event_processing_duration_seconds_max, highest sample",
        )
    )

    for service in containers:
        cpu = container_series(samples, service, "cpu_percent")
        memory = container_series(samples, service, "memory_bytes")
        add(
            bench_scenarios.measured(
                f"CPU of {service}, mean",
                "% of one core",
                bench_scenarios.mean(cpu),
                "docker stats, averaged over the samples of the window",
                "100% is one fully used core, a container may exceed it",
            )
        )
        add(
            bench_scenarios.measured(
                f"CPU of {service}, p95",
                "% of one core",
                bench_metrics.percentile(cpu, 0.95),
                "docker stats, p95 across the samples of the window",
            )
        )
        add(
            bench_scenarios.measured(
                f"CPU of {service}, peak",
                "% of one core",
                bench_scenarios.maximum(cpu),
                "docker stats, highest sample",
            )
        )
        add(
            bench_scenarios.measured(
                f"Memory of {service}, peak",
                "MiB",
                None if bench_scenarios.maximum(memory) is None else max(memory) / 1024**2,
                "docker stats, highest resident memory of the window",
            )
        )

    busiest_cpu = _peak_container(samples, containers, "cpu_percent")
    busiest_memory = _peak_container(samples, containers, "memory_bytes")
    add(
        bench_scenarios.measured(
            "CPU, highest container",
            "% of one core",
            None if busiest_cpu is None else busiest_cpu["value"],
            "docker stats, highest sample of any container of the run",
            f"container {busiest_cpu['service']}" if busiest_cpu else "",
        )
    )
    add(
        bench_scenarios.measured(
            "Memory, highest container",
            "MiB",
            None if busiest_memory is None else busiest_memory["value"] / 1024**2,
            "docker stats, highest resident memory of any container of the run",
            f"container {busiest_memory['service']}" if busiest_memory else "",
        )
    )

    observations = _observations(scenario, samples, final, window_seconds, produced)
    return {
        "scenario": {
            "vehicles": scenario.vehicles,
            "targetEventsPerSecond": scenario.target_events_per_second,
            "durationSeconds": scenario.duration_seconds,
            "duration": scenario.duration,
            "name": scenario.name,
        },
        "windowSeconds": window_seconds,
        "environment": environment,
        "quantities": [quantity.as_json() for quantity in quantities],
        "observations": observations,
        "latencySource": {
            "histogram": histogram_present,
            "gauge": gauge_present,
            "status": MEASURED if histogram_present else (UNAVAILABLE if not gauge_present else MEASURED),
        },
    }


def _sum_or_none(*values: float | None) -> float | None:
    present = [value for value in values if value is not None]
    if not present:
        return None
    return float(sum(present))


def _max_or_none(points: list[tuple[float, float]], factor: float = 1.0) -> float | None:
    values = [value for _, value in points]
    if not values:
        return None
    return max(values) * factor


def _peak_container(samples: list[dict], containers: dict[str, str], field: str) -> dict | None:
    """The container that used most of one resource, and how much it used."""
    peak: dict | None = None
    for service in containers:
        values = container_series(samples, service, field)
        if not values:
            continue
        highest = max(values)
        if peak is None or highest > peak["value"]:
            peak = {"service": service, "value": highest}
    return peak


def _mean_step_millis(samples: list[dict]) -> float | None:
    """Mean duration of one processing step: the change of the timer over the change of its count."""
    total = bench_metrics.counter_delta(series(samples, "processing_duration_sum"))
    count = bench_metrics.counter_delta(series(samples, "processing_duration_count"))
    if total is None or count is None or count <= 0:
        return None
    return total / count * 1000.0


def _observations(
    scenario: Scenario,
    samples: list[dict],
    final: dict[str, float | None],
    window_seconds: float,
    produced: float | None,
) -> list[dict]:
    """What the run itself says about its own validity, measured rather than assumed."""
    observations: list[dict] = []
    received = bench_metrics.counter_delta(series(samples, "received_total"))
    processed = bench_metrics.counter_delta(series(samples, "processed_total"))
    failed = bench_metrics.counter_delta(series(samples, "failed_total"))
    if received is not None and processed is not None and failed is not None:
        observations.append(
            {
                "observation": "the pipeline invariant received = processed + failed",
                "measured": f"{received:.0f} vs {processed:.0f} + {failed:.0f}",
                "held": abs(received - (processed + failed)) < 1e-6,
                "source": "logistics_events_{received,processed,failed}_total in the samples",
            }
        )
    steps = bench_metrics.counter_delta(series(samples, "processing_duration_count"))
    if steps is not None and processed is not None and processed > 0:
        observations.append(
            {
                "observation": "processing steps per accepted record",
                "measured": f"{steps / processed:.3f}",
                "expected": "3 (validation plus two detections), as docs/observability.md states",
                "source": "logistics_event_processing_duration_seconds_count / processed events",
            }
        )
    if produced is not None:
        observations.append(
            {
                "observation": "the target rate was sustained",
                "measured": f"{produced:.1f} events/s of {scenario.target_events_per_second:,}",
                "held": produced >= scenario.target_events_per_second * 0.95,
                "source": "coobi_generator_events_total{result=\"published\"} over the window",
            }
        )
    published = series(samples, "generator_published_total")
    if len(published) >= 2 and produced is not None:
        before_end = bench_metrics.sustained_rate(published[:-1])
        observations.append(
            {
                "observation": "the generator was still publishing at the end of the window",
                "measured": f"latest rate {before_end:.1f} events/s" if before_end else UNAVAILABLE,
                "held": before_end is not None and before_end > 0,
                "source": "the samples before the last one",
            }
        )
    degraded = [
        sample["t_utc"]
        for sample in samples
        if any(
            sample.get("metrics", {}).get(name, {}).get("value") is None
            for name in REQUIRED_SAMPLE_METRICS
        )
    ]
    observations.append(
        {
            "observation": "every series the summary needs answered in every sample",
            "measured": f"{len(samples) - len(degraded)} of {len(samples)} samples complete",
            "held": not degraded,
            "source": "the raw samples of raw.jsonl",
        }
    )
    observations.append(
        {
            "observation": "the measured window has the duration of the scenario",
            "measured": f"{window_seconds:.1f}s of {scenario.duration_seconds:.0f}s",
            "held": abs(window_seconds - scenario.duration_seconds) <= max(2.0, scenario.duration_seconds * 0.02),
            "source": "wall clock of the harness between the first and the last sample",
        }
    )
    return observations


def environment_facts(docker: Docker, prometheus: Prometheus, services: tuple[str, ...]) -> dict:
    """The hardware, the software and the settings a reader needs to reproduce a run."""
    info = docker.info()
    facts: dict = {
        "host": {
            "platform": platform.platform(),
            "system": platform.system(),
            "release": platform.release(),
            "machine": platform.machine(),
            "processor": platform.processor() or None,
            "cpuModel": cpu_model(),
            "cpuCount": os.cpu_count(),
            "memoryBytes": physical_memory_bytes(),
        },
        "docker": {
            "serverVersion": docker.server_version(),
            "composeVersion": docker.client_version(),
            "operatingSystem": info.get("OperatingSystem"),
            "kernelVersion": info.get("KernelVersion"),
            "cpus": info.get("NCPU"),
            "memoryBytes": info.get("MemTotal"),
        },
        "repository": repository_state(),
        "images": declared_images(),
        "java": {},
        "kafka": {},
    }
    for service in ("event-generator", "stream-processor", "logistics-api"):
        settings = docker.exec(service, ["java", "-XshowSettings:vm", "-version"])
        facts["java"][service] = parse_java_settings(settings)
    described = docker.exec(
        "kafka",
        [
            "/opt/kafka/bin/kafka-topics.sh",
            "--bootstrap-server",
            "localhost:9092",
            "--describe",
            "--topic",
            LOCATION_TOPIC,
        ],
    )
    facts["kafka"]["locationTopic"] = parse_topic_description(described)
    facts["prometheus"] = {"scrapeIntervalSeconds": scrape_interval_seconds(prometheus)}
    facts["benchmarkServices"] = list(services)
    return facts


def cpu_model() -> str | None:
    """The model of the CPU, where the platform exposes one."""
    system = platform.system()
    try:
        if system == "Windows":
            import winreg  # noqa: PLC0415 - only this platform has it

            with winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"HARDWARE\DESCRIPTION\System\CentralProcessor\0",
            ) as key:
                return str(winreg.QueryValueEx(key, "ProcessorNameString")[0]).strip()
        if system == "Linux":
            with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as cpuinfo:
                for line in cpuinfo:
                    if line.lower().startswith("model name"):
                        return line.split(":", 1)[1].strip()
        if system == "Darwin":
            result = run_command(["sysctl", "-n", "machdep.cpu.brand_string"])
            if result.returncode == 0:
                return result.stdout.strip() or None
    except (OSError, ImportError, IndexError):
        return None
    return None


def physical_memory_bytes() -> int | None:
    """Total memory of the machine, without a third-party library."""
    try:
        if platform.system() == "Windows":
            import ctypes  # noqa: PLC0415

            class MemoryStatus(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong),
                    ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong),
                    ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = MemoryStatus()
            status.dwLength = ctypes.sizeof(MemoryStatus)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return int(status.ullTotalPhys)
            return None
        page_size = os.sysconf("SC_PAGE_SIZE")
        pages = os.sysconf("SC_PHYS_PAGES")
        return int(page_size) * int(pages)
    except (OSError, ValueError, AttributeError):
        return None


def repository_state() -> dict:
    """The commit a run belongs to, and whether the tree it ran from was dirty."""
    commit = run_command(["git", "rev-parse", "HEAD"])
    status = run_command(["git", "status", "--porcelain"])
    return {
        "commit": commit.stdout.strip() or None if commit.returncode == 0 else None,
        "dirty": bool(status.stdout.strip()) if status.returncode == 0 else None,
    }


def declared_images() -> dict:
    """The base images of the stack, as declared: what software versions a run actually used."""
    images = {}
    compose_text = (REPO_ROOT / COMPOSE_FILE).read_text(encoding="utf-8")
    for service, image in re.findall(r"(?m)^  (\S+):\n(?:.*\n)*?    image: (\S+)$", compose_text):
        images[service] = image
    for service in ("event-generator", "stream-processor", "logistics-api"):
        dockerfile = REPO_ROOT / "services" / service / "Dockerfile"
        if dockerfile.exists():
            stages = re.findall(r"(?m)^FROM\s+(\S+)", dockerfile.read_text(encoding="utf-8"))
            images[service] = {"buildStages": stages}
    frontend_dockerfile = REPO_ROOT / "frontend" / "Dockerfile"
    if frontend_dockerfile.exists():
        images[FRONTEND_SERVICE] = {
            "buildStages": re.findall(
                r"(?m)^FROM\s+(\S+)", frontend_dockerfile.read_text(encoding="utf-8")
            )
        }
    return images


def parse_java_settings(text: str | None) -> dict:
    """The Java version and the heap settings of a running container, as the JVM reports them."""
    if not text:
        return {"status": UNAVAILABLE}
    version = re.search(r'version "([^"]+)"', text)
    # The line the JVM prints is `Max. Heap Size (Estimated): 3.88G`, with the qualification
    # between the label and the colon, so the separator is not assumed to follow the label.
    heap = re.search(r"Max\.\s*Heap Size[^:\n]*:\s*([^\n(]+)", text)
    return {
        "status": MEASURED,
        "version": version.group(1) if version else None,
        "maxHeap": heap.group(1).strip() if heap else None,
    }


def parse_topic_description(text: str | None) -> dict:
    """The partition count and the replication factor of the location topic."""
    if not text:
        return {"status": UNAVAILABLE}
    partitions = re.search(r"PartitionCount:\s*(\d+)", text)
    replication = re.search(r"ReplicationFactor:\s*(\d+)", text)
    return {
        "status": MEASURED,
        "partitionCount": int(partitions.group(1)) if partitions else None,
        "replicationFactor": int(replication.group(1)) if replication else None,
    }


def scrape_interval_seconds(prometheus: Prometheus) -> float | None:
    """The scrape interval Prometheus is really configured with, in seconds."""
    try:
        url = f"{prometheus.base_url}/api/v1/status/config"
        with urllib.request.urlopen(url, timeout=prometheus.timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError, BenchmarkError):
        return None
    match = re.search(r"scrape_interval:\s*([0-9a-z]+)", payload.get("data", {}).get("yaml", ""))
    if match is None:
        return None
    try:
        return bench_scenarios.parse_duration(match.group(1))
    except bench_scenarios.BenchmarkInputError:
        return None


# ---------------------------------------------------------------------------------------------
# Running a scenario
# ---------------------------------------------------------------------------------------------

NOT_RUN_METRICS: tuple[tuple[str, str, str], ...] = (
    ("Events produced", "events/s", 'prometheus: coobi_generator_events_total{result="published"}'),
    ("Events processed", "events/s", "prometheus: logistics_events_processed_total"),
    ("Events failed", "events/s", "prometheus: logistics_events_failed_total"),
    ("Processing latency p50", "ms", "prometheus: histogram_quantile over the window"),
    ("Processing latency p95", "ms", "prometheus: histogram_quantile over the window"),
    ("Processing latency p99", "ms", "prometheus: histogram_quantile over the window"),
    (
        "Consumer lag, broker group total",
        "records",
        "kafka-consumer-groups.sh --describe, committed offset against the end of the log",
    ),
    ("CPU of the busiest container", "% of one core", "docker stats"),
    ("Memory of the busiest container", "MiB", "docker stats"),
)


def not_run_summary(scenario: Scenario, reason: str) -> dict:
    """The result of a scenario that did not run: every metric labelled instead of valued."""
    return {
        "scenario": {
            "vehicles": scenario.vehicles,
            "targetEventsPerSecond": scenario.target_events_per_second,
            "durationSeconds": scenario.duration_seconds,
            "duration": scenario.duration,
            "name": scenario.name,
        },
        "windowSeconds": None,
        "environment": {},
        "quantities": [
            bench_scenarios.not_run(metric, unit, source, reason).as_json()
            for metric, unit, source in NOT_RUN_METRICS
        ],
        "observations": [
            {
                "observation": "the scenario ran",
                "measured": NOT_RUN,
                "held": False,
                "source": reason,
            }
        ],
        "latencySource": {"histogram": False, "gauge": False, "status": NOT_RUN},
        "failure": reason,
    }


def scenario_environment(scenario: Scenario, window_allowance: float) -> dict[str, str]:
    """The environment the benchmark overlay interpolates for one scenario."""
    load_test_duration = scenario.duration_seconds + window_allowance
    return {
        "BENCH_VEHICLES": str(scenario.vehicles),
        "BENCH_TARGET_EVENTS_PER_SECOND": str(scenario.target_events_per_second),
        # The generator stops itself when this elapses, so the load of a run the harness loses
        # control of cannot go on forever. The harness ends the measured window before it.
        "BENCH_LOAD_TEST_DURATION": bench_scenarios.format_duration(load_test_duration),
    }


def run_scenario(scenario: Scenario, argument: argparse.Namespace, output_root: Path) -> dict:
    """Start, sample and summarize one scenario; never raise for a stack that misbehaves."""
    run_dir = output_root / scenario.name
    run_dir.mkdir(parents=True, exist_ok=True)
    raw_path = run_dir / "raw.jsonl"
    raw_path.write_text("", encoding="utf-8")

    prometheus = Prometheus(argument.prometheus_url)
    environment = {**os.environ, **scenario_environment(scenario, argument.startup_allowance)}
    docker = Docker([COMPOSE_FILE, BENCHMARK_COMPOSE_FILE], environment)
    services = PIPELINE_SERVICES + ((FRONTEND_SERVICE,) if argument.frontend else ())
    global_options = ["--profile", FRONTEND_PROFILE] if argument.frontend else []
    context = RunContext(
        scenario=scenario,
        argument=argument,
        docker=docker,
        prometheus=prometheus,
        run_dir=run_dir,
        compose_environment=environment,
        services=services,
        global_options=global_options,
    )

    log(f"scenario {scenario}: resetting the stack")
    reset_stack(context)
    log(f"scenario {scenario}: starting {', '.join(services)}")
    start_stack(context)

    health = wait_for_health(docker, services, argument.startup_timeout)
    log(f"scenario {scenario}: the stack is healthy, waiting for the scrape targets")
    targets = wait_for_targets(prometheus, argument.target_timeout)
    containers = docker.container_ids([service for service in CONTAINER_SERVICES if service in services])
    facts = environment_facts(docker, prometheus, services)
    log(f"scenario {scenario}: measuring {scenario.duration}")

    samples, window_seconds = measure_window(context, containers)
    window_seconds = measured_window_seconds(samples, window_seconds)
    lag = broker_consumer_lag(docker, STREAMS_APPLICATION_ID)
    log(f"scenario {scenario}: the window is over, stopping the generator")
    stop_generator(context)

    final = {name: value[0] for name, value in last_window_values(samples).items()}
    summary = summarize(scenario, samples, final, window_seconds, containers, facts, lag)
    summary["run"] = {
        "directory": str(run_dir.relative_to(REPO_ROOT)).replace("\\", "/"),
        "startedUtc": samples[0]["t_utc"] if samples else None,
        "finishedUtc": samples[-1]["t_utc"] if samples else None,
        "sampleCount": len(samples),
        "sampleIntervalSeconds": argument.sample_interval,
        "loadTestDurationSeconds": scenario.duration_seconds + argument.startup_allowance,
        "health": health,
        "targets": targets,
        "volumesReset": argument.reset_volumes,
        "frontend": argument.frontend,
        "composeCommand": " ".join(docker.compose_command(["up", "-d", *services], global_options)),
        "composeEnvironment": {
            "BENCH_VEHICLES": environment["BENCH_VEHICLES"],
            "BENCH_TARGET_EVENTS_PER_SECOND": environment["BENCH_TARGET_EVENTS_PER_SECOND"],
            "BENCH_LOAD_TEST_DURATION": environment["BENCH_LOAD_TEST_DURATION"],
        },
    }
    (run_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (run_dir / "report.md").write_text(
        render_scenario_report(summary, scenario), encoding="utf-8"
    )
    return summary


def reset_stack(context: RunContext) -> None:
    """Stop the stack a scenario starts from.

    The volumes are kept by default: they hold the local Kafka topics, the read model and the
    metrics of previous runs, and a benchmark is not a reason to delete them. ``--reset-volumes``
    is the switch that does delete them, for a run that has to start from an empty topic.
    """
    args = ["down", "--remove-orphans"]
    if context.argument.reset_volumes:
        args.insert(1, "-v")
    result = context.docker.compose(args)
    if result.returncode != 0:
        log(f"docker compose {' '.join(args)} reported: {result.stderr.strip()}")


def start_stack(context: RunContext) -> None:
    """Build (once per scenario, cached by Docker) and start the services of the run."""
    args = ["up", "-d"]
    if not context.argument.no_build:
        args.append("--build")
    args += list(context.services)
    result = context.docker.compose(args, context.global_options)
    if result.returncode != 0:
        raise BenchmarkError(
            f"docker compose {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}"
        )


def stop_generator(context: RunContext) -> None:
    """End the load where the measured window ends, without touching the rest of the stack."""
    result = context.docker.compose(["stop", "event-generator"])
    if result.returncode != 0:
        log(f"docker compose stop event-generator reported: {result.stderr.strip()}")


def measure_window(context: RunContext, containers: dict[str, str]) -> tuple[list[dict], float]:
    """Sample the stack for the duration of the scenario and return the samples and the window."""
    scenario = context.scenario
    derived = window_queries(scenario.duration_seconds)
    started = time.monotonic()
    end = started + scenario.duration_seconds
    samples: list[dict] = []

    def record(sample: dict) -> None:
        samples.append(sample)
        with open(context.run_dir / "raw.jsonl", "a", encoding="utf-8") as raw:
            raw.write(json.dumps(sample, ensure_ascii=False) + "\n")

    first = take_sample(context, SAMPLE_QUERIES, containers)
    record(first)
    if first["duration_ms"] > context.argument.sample_interval * 1000:
        log(
            "one sample takes "
            f"{first['duration_ms'] / 1000:.1f}s, longer than the "
            f"{context.argument.sample_interval:g}s interval: the window will hold fewer samples "
            "than the interval suggests, and the report states how many it holds"
        )
    while True:
        remaining = end - time.monotonic()
        if remaining <= context.argument.sample_interval:
            # The last sample of the window, taken whether or not the previous ones left time for
            # it: it carries the derived queries of the window, so the percentiles describe
            # exactly the seconds that were measured.
            if remaining > 0:
                time.sleep(remaining)
            record(take_sample(context, {**SAMPLE_QUERIES, **derived}, containers))
            break
        time.sleep(context.argument.sample_interval)
        record(take_sample(context, SAMPLE_QUERIES, containers))
    return samples, time.monotonic() - started


def last_window_values(samples: list[dict]) -> dict[str, tuple[float, float]]:
    """The derived queries of the last sample: ``{name: (value, timestamp)}``."""
    if not samples:
        return {}
    values = {}
    for name, entry in samples[-1].get("metrics", {}).items():
        if entry.get("value") is None or name in SAMPLE_QUERIES:
            continue
        values[name] = (entry["value"], entry.get("timestamp", 0.0))
    return values


def measured_window_seconds(samples: list[dict], fallback: float) -> float:
    """How much time the captured samples actually cover.

    The window is measured between the Prometheus evaluation timestamps of the first and the
    last sample rather than from the wall clock of the harness: the samples are what the report
    is computed from, and the seconds spent issuing the last of them are not part of the window
    they describe.
    """
    timestamps = [
        entry["timestamp"]
        for sample in samples
        for entry in sample.get("metrics", {}).values()
        if entry.get("timestamp") is not None
    ]
    if len(timestamps) < 2:
        return fallback
    covered = max(timestamps) - min(timestamps)
    return covered if covered > 0 else fallback


def broker_consumer_lag(docker: Docker, group: str) -> dict:
    """The consumer lag the broker itself reports for the group of the stream processor.

    The client metric of the streams application and the committed offsets of the broker are two
    different measurements of the same backlog, and the broker's is the authoritative one: it
    compares the committed offset of every partition with the end of the log. The command is the
    Kafka CLI of the broker container, so what it prints is the broker's own view.
    """
    output = docker.exec(
        "kafka",
        [
            "/opt/kafka/bin/kafka-consumer-groups.sh",
            "--bootstrap-server",
            "localhost:9092",
            "--describe",
            "--group",
            group,
        ],
    )
    if not output:
        return {"status": UNAVAILABLE, "source": "kafka-consumer-groups.sh", "reason": "no output"}
    total = 0
    partitions = 0
    highest = 0
    for line in output.splitlines():
        columns = line.split()
        if len(columns) < 6 or columns[0] != group or columns[1] != LOCATION_TOPIC:
            continue
        try:
            lag = int(columns[5])
        except ValueError:
            continue
        total += lag
        highest = max(highest, lag)
        partitions += 1
    if partitions == 0:
        return {
            "status": UNAVAILABLE,
            "source": "kafka-consumer-groups.sh",
            "reason": "the broker listed no partition of the consumed topic",
        }
    return {
        "status": MEASURED,
        "source": "kafka-consumer-groups.sh --describe, committed offset against the end of the log",
        "records": total,
        "highestPartitionRecords": highest,
        "partitions": partitions,
    }


def render_scenario_report(summary: dict, scenario: Scenario) -> str:
    """The Markdown fragment of one scenario, ready to be read or copied into docs/benchmarks.md."""
    run = summary.get("run", {})
    lines = [
        f"# Benchmark scenario {scenario.name}",
        "",
        "| Scenario | Value |",
        "| --- | --- |",
        f"| Vehicles | {scenario.vehicles:,} |",
        f"| Target events per second | {scenario.target_events_per_second:,} |",
        f"| Measured window | {scenario.duration} "
        f"({summary.get('windowSeconds') or 'not measured'} seconds of wall clock) |",
        f"| Samples | {run.get('sampleCount', NOT_RUN)} every "
        f"{render_seconds_or_none(run.get('sampleIntervalSeconds'))} |",
        f"| Started (UTC) | {run.get('startedUtc', NOT_RUN)} |",
        f"| Finished (UTC) | {run.get('finishedUtc', NOT_RUN)} |",
        f"| Local volumes reset | {'yes' if run.get('volumesReset') else 'no'} |",
        f"| Dashboard in the run | {'yes' if run.get('frontend') else 'no'} |",
        "",
    ]
    if summary.get("failure"):
        lines += [f"**This scenario did not run**: {summary['failure']}", ""]
    lines += ["## Results", "", "| Metric | Value | Unit | Status | Source |", "| --- | --- | --- | --- | --- |"]
    for quantity in summary.get("quantities", []):
        note = quantity.get("note") or ""
        source = quantity["source"] + (f" ({note})" if note else "")
        lines.append(
            f"| {quantity['metric']} | {render_value(quantity)} | {quantity['unit']} | "
            f"{quantity['status']} | {source} |"
        )
    observations = summary.get("observations", [])
    if observations:
        lines += [
            "",
            "## Observations of the run",
            "",
            "| Observation | Measured | Held | Source |",
            "| --- | --- | --- | --- |",
        ]
        for observation in observations:
            held = observation.get("held")
            lines.append(
                f"| {observation['observation']} | {observation['measured']} | "
                f"{'yes' if held else ('no' if held is False else '-')} | {observation['source']} |"
            )
    environment = summary.get("environment") or {}
    if environment:
        lines += ["", "## Environment of the run", ""]
        host = environment.get("host", {})
        docker_facts = environment.get("docker", {})
        repository = environment.get("repository", {})
        lines += [
            f"- Host: {host.get('cpuModel') or UNAVAILABLE}, "
            f"{host.get('cpuCount') or UNAVAILABLE} logical CPUs, "
            f"{render_bytes(host.get('memoryBytes'))} of memory, "
            f"{host.get('platform') or UNAVAILABLE}",
            f"- Docker: server {docker_facts.get('serverVersion') or UNAVAILABLE}, Compose "
            f"{docker_facts.get('composeVersion') or UNAVAILABLE}, "
            f"{docker_facts.get('cpus') or UNAVAILABLE} CPUs and "
            f"{render_bytes(docker_facts.get('memoryBytes'))} of memory visible to the engine",
            f"- Repository: {repository.get('commit') or UNAVAILABLE} "
            f"({'dirty' if repository.get('dirty') else 'clean'})",
            f"- Prometheus scrape interval: "
            f"{render_seconds_or_none(environment.get('prometheus', {}).get('scrapeIntervalSeconds'))}",
        ]
        for service, java in (environment.get("java") or {}).items():
            if java.get("status") == MEASURED:
                lines.append(
                    f"- JVM of {service}: {java.get('version')}, max heap {java.get('maxHeap')}"
                )
            else:
                lines.append(f"- JVM of {service}: {UNAVAILABLE}")
        kafka = environment.get("kafka", {}).get("locationTopic", {})
        if kafka.get("status") == MEASURED:
            lines.append(
                f"- Kafka topic {LOCATION_TOPIC}: {kafka.get('partitionCount')} partitions, "
                f"replication factor {kafka.get('replicationFactor')}"
            )
        else:
            lines.append(f"- Kafka topic {LOCATION_TOPIC}: {UNAVAILABLE}")
    lines += ["", "## Raw capture", "", f"`{run.get('directory', NOT_RUN)}/raw.jsonl`"]
    return "\n".join(lines) + "\n"


def render_value(quantity: dict) -> str:
    """The value cell of a quantity: a number only when the quantity was measured."""
    if quantity["status"] != MEASURED or quantity["value"] is None:
        return quantity["status"]
    return bench_scenarios.format_number(quantity["value"])


def render_bytes(value: float | int | None) -> str:
    if value is None:
        return UNAVAILABLE
    return f"{value / 1024**3:.1f} GiB"


def render_seconds_or_none(value: float | None) -> str:
    """A duration of the run, or the fact that the run did not state one."""
    if value is None:
        return NOT_RUN
    try:
        return bench_scenarios.format_duration(float(value))
    except (TypeError, ValueError):
        return NOT_RUN


def render_matrix_report(summaries: list[dict], label: str) -> str:
    """The results table of a matrix or of a set of scenarios, one row per scenario."""
    columns = [
        ("Scenario", lambda summary: summary["scenario"]["name"]),
        ("Events produced/s", lambda summary: render_metric(summary, "Events produced")),
        ("Processed/s", lambda summary: render_metric(summary, "Events processed")),
        ("Failed/s", lambda summary: render_metric(summary, "Events failed")),
        ("p50 (ms)", lambda summary: render_metric(summary, "Processing latency p50")),
        ("p95 (ms)", lambda summary: render_metric(summary, "Processing latency p95")),
        ("p99 (ms)", lambda summary: render_metric(summary, "Processing latency p99")),
        ("Lag (broker, records)", lambda summary: render_metric(summary, "Consumer lag, broker group total")),
        ("CPU peak", lambda summary: render_metric(summary, "CPU, highest container")),
        ("RAM peak (MiB)", lambda summary: render_metric(summary, "Memory, highest container")),
    ]
    lines = [
        f"# MVP-11 benchmark results ({label})",
        "",
        "| " + " | ".join(name for name, _ in columns) + " |",
        "| " + " | ".join("---" for _ in columns) + " |",
    ]
    for summary in summaries:
        lines.append("| " + " | ".join(render(summary) for _, render in columns) + " |")
    lines += [
        "",
        "`unavailable` means the stack does not expose that measurement, and `not run` means the "
        "scenario did not produce one. Neither is a zero.",
        "",
    ]
    return "\n".join(lines)


def render_metric(summary: dict, metric: str) -> str:
    for quantity in summary.get("quantities", []):
        if quantity["metric"] == metric:
            return render_value(quantity)
    return NOT_RUN


# ---------------------------------------------------------------------------------------------
# Command line
# ---------------------------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="benchmark.py",
        description=(
            "Measure the Dockerized Coobi Logistics stack: run one scenario or the matrix of "
            "MVP-11.2 and write the raw samples, a summary and a report per scenario."
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "The four scenarios of MVP-11.2:\n"
            "  --matrix\n"
            "\n"
            "A single scenario, with its own window:\n"
            "  --scenario 10000/25000/5m\n"
            "\n"
            "A bounded smoke run of the same shape:\n"
            "  --scenario 1000/1000/60s --sample-interval 5s\n"
        ),
    )
    parser.add_argument(
        "--scenario",
        action="append",
        default=[],
        metavar="VEHICLES/RATE/DURATION",
        help="scenario to run, for example 5000/10000/5m; repeatable",
    )
    parser.add_argument(
        "--matrix",
        action="store_true",
        help="run the four scenarios of MVP-11.2: 1000/1000, 5000/10000, 10000/25000 and 20000/50000",
    )
    parser.add_argument(
        "--duration",
        default="5m",
        metavar="DURATION",
        help="window of the scenarios of --matrix (default 5m)",
    )
    parser.add_argument(
        "--sample-interval",
        default="10s",
        metavar="DURATION",
        help="how often the stack is sampled while a scenario runs (default 10s)",
    )
    parser.add_argument(
        "--startup-timeout",
        default="15m",
        metavar="DURATION",
        help="how long to wait for the stack to become healthy (default 15m; the first build is slow)",
    )
    parser.add_argument(
        "--target-timeout",
        default="3m",
        metavar="DURATION",
        help="how long to wait for both Prometheus scrape targets (default 3m)",
    )
    parser.add_argument(
        "--startup-allowance",
        default="3m",
        metavar="DURATION",
        help=(
            "how much longer than the window the generator is allowed to publish (default 3m); "
            "the harness stops the load at the end of the window, so this is the safety net of a "
            "run the harness loses control of"
        ),
    )
    parser.add_argument(
        "--results-dir",
        default=str(RESULTS_ROOT),
        metavar="PATH",
        help=f"where the results are written (default {RESULTS_ROOT})",
    )
    parser.add_argument(
        "--prometheus-url",
        default=DEFAULT_PROMETHEUS_URL,
        metavar="URL",
        help=f"Prometheus of the stack (default {DEFAULT_PROMETHEUS_URL})",
    )
    parser.add_argument(
        "--no-build",
        action="store_true",
        help="start the images that exist instead of building them (faster after a first run)",
    )
    parser.add_argument(
        "--reset-volumes",
        action="store_true",
        help=(
            "delete the local volumes between scenarios (docker compose down -v), so every "
            "scenario starts from an empty Kafka and an empty read model; the default keeps "
            "them, and a scenario then inherits the telemetry of the previous one"
        ),
    )
    parser.add_argument(
        "--frontend",
        action="store_true",
        help=(
            "include the dashboard in the run; a benchmark leaves it out by default, so the "
            "measurement is of the pipeline"
        ),
    )
    parser.add_argument(
        "--stop-stack",
        action="store_true",
        help="stop the stack when every scenario has run; the default leaves it up",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="print the plan of the run without starting or measuring anything",
    )
    parser.add_argument(
        "--list",
        dest="list_only",
        action="store_true",
        help="print the scenarios that would run and exit",
    )
    return parser


def scenarios_from(argument: argparse.Namespace) -> list[Scenario]:
    """The scenarios of an invocation: what was asked for, validated before anything starts."""
    if argument.matrix and argument.scenario:
        raise BenchmarkInputError("choose --matrix or --scenario, not both")
    if argument.matrix:
        return list(bench_scenarios.scale_matrix(parse_duration_argument(argument.duration, "--duration")))
    if argument.scenario:
        return [bench_scenarios.parse_scenario(text) for text in argument.scenario]
    raise BenchmarkInputError("nothing to run: pass --matrix or at least one --scenario")


def parse_duration_argument(text: str, option: str) -> float:
    try:
        return bench_scenarios.parse_duration(text)
    except bench_scenarios.BenchmarkInputError as invalid:
        raise BenchmarkInputError(f"{option}: {invalid}") from invalid


def describe_plan(scenarios: list[Scenario], argument: argparse.Namespace) -> str:
    """The plan of a run, as it is printed before anything starts."""
    services = PIPELINE_SERVICES + ((FRONTEND_SERVICE,) if argument.frontend else ())
    lines = [
        f"Scenarios ({len(scenarios)}):",
        "",
        "| Vehicles | Target events/s | Window |",
        "| --- | --- | --- |",
    ]
    for scenario in scenarios:
        lines.append(
            f"| {scenario.vehicles:,} | {scenario.target_events_per_second:,} | {scenario.duration} |"
        )
    lines += [
        "",
        f"Sample interval: {bench_scenarios.format_duration(argument.sample_interval)}",
        "Services: " + ", ".join(services),
        f"Compose: {COMPOSE_FILE} + {BENCHMARK_COMPOSE_FILE}",
        "Local volumes: "
        + ("deleted before each scenario" if argument.reset_volumes else "kept, as the default"),
        "Dashboard: " + ("included" if argument.frontend else "left out of the measurement"),
    ]
    return "\n".join(lines)


def prepare_output(argument: argparse.Namespace, scenarios: list[Scenario]) -> Path:
    """The directory of this invocation, one sub-directory per scenario."""
    label = "matrix" if argument.matrix else ("scenario" if len(scenarios) == 1 else "selected")
    if len(scenarios) == 1 and not argument.matrix:
        label = scenarios[0].name
    root = Path(argument.results_dir) / f"{run_directory_name()}-{label}"
    root.mkdir(parents=True, exist_ok=True)
    (root / "run.json").write_text(
        json.dumps(
            {
                "command": " ".join([sys.executable, *sys.argv]),
                "startedUtc": utc_now(),
                "scenarios": [scenario.name for scenario in scenarios],
                "sampleIntervalSeconds": argument.sample_interval,
                "startupAllowanceSeconds": argument.startup_allowance,
                "volumesReset": argument.reset_volumes,
                "frontend": argument.frontend,
                "imagesBuilt": not argument.no_build,
                "composeFiles": [COMPOSE_FILE, BENCHMARK_COMPOSE_FILE],
                "prometheusUrl": argument.prometheus_url,
            },
            indent=2,
            ensure_ascii=False,
        )
        + "\n",
        encoding="utf-8",
    )
    return root


def write_failed_scenario(scenario: Scenario, reason: str, output_root: Path) -> dict:
    """Record a scenario that did not run, and name every metric as not run rather than zero."""
    run_dir = output_root / scenario.name
    run_dir.mkdir(parents=True, exist_ok=True)
    summary = not_run_summary(scenario, reason)
    summary["run"] = {"directory": str(run_dir.relative_to(REPO_ROOT)).replace("\\", "/")}
    (run_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (run_dir / "report.md").write_text(render_scenario_report(summary, scenario), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    argument = parser.parse_args(argv)
    try:
        scenarios = scenarios_from(argument)
        argument.sample_interval = parse_duration_argument(argument.sample_interval, "--sample-interval")
        argument.startup_timeout = parse_duration_argument(argument.startup_timeout, "--startup-timeout")
        argument.target_timeout = parse_duration_argument(argument.target_timeout, "--target-timeout")
        argument.startup_allowance = parse_duration_argument(
            argument.startup_allowance, "--startup-allowance"
        )
    except BenchmarkInputError as invalid:
        parser.error(str(invalid))
        return 2

    print(describe_plan(scenarios, argument))
    if argument.list_only or argument.dry_run:
        return 0
    if shutil.which("docker") is None:
        log("the docker CLI is not on the PATH, so no scenario can run")
        return 2

    output_root = prepare_output(argument, scenarios)
    log(f"results of this invocation: {output_root}")
    summaries: list[dict] = []
    failed = 0
    for scenario in scenarios:
        try:
            summaries.append(run_scenario(scenario, argument, output_root))
        except KeyboardInterrupt:
            log("interrupted: recording the scenario as not run")
            summaries.append(
                write_failed_scenario(scenario, "interrupted by the operator", output_root)
            )
            failed += 1
            break
        except BenchmarkError as failure:
            log(f"scenario {scenario} did not run: {failure}")
            summaries.append(write_failed_scenario(scenario, str(failure), output_root))
            failed += 1

    (output_root / "matrix.json").write_text(
        json.dumps(summaries, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    label = "MVP-11.2 required matrix" if argument.matrix else "selected scenarios"
    (output_root / "matrix.md").write_text(
        render_matrix_report(summaries, label), encoding="utf-8"
    )
    print(render_matrix_report(summaries, label))
    log(f"results written to {output_root}")
    if argument.stop_stack:
        log("stopping the stack")
        Docker(
            [COMPOSE_FILE, BENCHMARK_COMPOSE_FILE],
            {**os.environ, "BENCH_VEHICLES": "1", "BENCH_TARGET_EVENTS_PER_SECOND": "1",
             "BENCH_LOAD_TEST_DURATION": "1s"},
        ).compose(["down", "--remove-orphans"])
    else:
        log("the stack is left running; docs/deployment.md documents how to stop it")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
