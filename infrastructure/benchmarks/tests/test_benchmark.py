"""Deterministic tests of the harness: from raw samples to the cells of a report.

Nothing here starts Docker or answers HTTP. The samples are the shape of the ones
``raw.jsonl`` holds (two counter samples, a container sample and the derived queries of the
window), so the arithmetic the report depends on is pinned without a stack.
"""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import sys
import unittest
from pathlib import Path

BENCHMARKS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BENCHMARKS))

import benchmark  # noqa: E402
import scenarios  # noqa: E402
from scenarios import MEASURED, NOT_RUN, UNAVAILABLE, Scenario  # noqa: E402


def sample(
    timestamp: float,
    counters: dict[str, float],
    containers: dict[str, dict] | None = None,
    derived: dict[str, float] | None = None,
) -> dict:
    """One sample of a raw capture, with the shape the harness writes."""
    metrics = {
        name: {"value": value, "timestamp": timestamp} for name, value in counters.items()
    }
    for name, value in (derived or {}).items():
        metrics[name] = {"value": value, "timestamp": timestamp}
    return {
        "t_utc": f"2026-09-21T09:00:{int(timestamp):02d}Z",
        "metrics": metrics,
        "containers": containers or {},
    }


def window_samples(
    processed: float = 6000.0,
    produced: float = 6000.0,
    steps: float = 18_000.0,
    step_seconds: float = 0.0002,
) -> list[dict]:
    """A window of two samples sixty seconds apart, at the rate of a small scenario."""
    container_samples = {
        "event-generator": {
            "container": "coobi-event-generator-1",
            "cpu_percent": 20.0,
            "memory_bytes": 100.0 * 1024**2,
        },
        "stream-processor": {
            "container": "coobi-stream-processor-1",
            "cpu_percent": 180.0,
            "memory_bytes": 900.0 * 1024**2,
        },
    }
    return [
        sample(
            0.0,
            {
                "generator_published_total": 0.0,
                "received_total": 0.0,
                "processed_total": 0.0,
                "failed_total": 0.0,
                "alerts_speeding_total": 0.0,
                "alerts_stopped_total": 0.0,
                "processing_duration_count": 0.0,
                "processing_duration_sum": 0.0,
                "processing_duration_max": 0.0,
                "consumer_lag_max": 0.0,
            },
            container_samples,
        ),
        sample(
            60.0,
            {
                "generator_published_total": produced,
                "received_total": processed,
                "processed_total": processed,
                "failed_total": 0.0,
                "alerts_speeding_total": 10.0,
                "alerts_stopped_total": 5.0,
                "processing_duration_count": steps,
                "processing_duration_sum": steps * step_seconds,
                "processing_duration_max": 0.005,
                "consumer_lag_max": 42.0,
            },
            container_samples,
        ),
    ]


def quantity(summary: dict, metric: str) -> dict:
    for entry in summary["quantities"]:
        if entry["metric"] == metric:
            return entry
    raise AssertionError(f"the summary holds no quantity '{metric}'")


def observation(summary: dict, what: str) -> dict:
    for entry in summary["observations"]:
        if entry["observation"] == what:
            return entry
    raise AssertionError(f"the summary holds no observation '{what}'")


class SummarizeTest(unittest.TestCase):
    scenario = Scenario(1000, 1000, 60.0)
    final = {
        "latency_p50_histogram": 0.0005,
        "latency_p95_histogram": 0.002,
        "latency_p99_histogram": 0.005,
        "latency_p50_gauge_average": 0.0006,
        "latency_p95_gauge_average": 0.0025,
        "latency_p99_gauge_average": 0.006,
    }

    def summarize(self, samples: list[dict] | None = None, final: dict | None = None) -> dict:
        return benchmark.summarize(
            self.scenario,
            samples if samples is not None else window_samples(),
            self.final if final is None else final,
            60.0,
            {"event-generator": "one", "stream-processor": "two"},
            {"host": {"cpuModel": "test", "cpuCount": 8}},
        )

    def test_throughput_is_the_rate_of_the_window(self) -> None:
        summary = self.summarize()

        produced = quantity(summary, "Events produced")
        self.assertEqual(produced["status"], MEASURED)
        self.assertEqual(produced["value"], 100.0)
        self.assertEqual(quantity(summary, "Events produced, total")["value"], 6000.0)
        self.assertEqual(quantity(summary, "Events processed")["value"], 100.0)
        self.assertEqual(quantity(summary, "Events failed")["value"], 0.0)
        self.assertEqual(quantity(summary, "Share of the target sustained")["value"], 10.0)
        self.assertEqual(quantity(summary, "Alerts generated")["value"], 15.0)
        self.assertEqual(quantity(summary, "Consumer lag, peak of the streams client metric")["value"], 42.0)
        # The broker reports the group lag of the end of a window; without it the summary says
        # so instead of repeating the client metric as if it were the same measurement.
        self.assertEqual(quantity(summary, "Consumer lag, broker group total")["status"], UNAVAILABLE)

    def test_the_broker_lag_is_reported_when_the_broker_answered(self) -> None:
        summary = benchmark.summarize(
            self.scenario,
            window_samples(),
            self.final,
            60.0,
            {"event-generator": "one"},
            {},
            {
                "status": MEASURED,
                "source": "kafka-consumer-groups.sh --describe, committed offset against the end of the log",
                "records": 1234,
                "highestPartitionRecords": 900,
                "partitions": 6,
            },
        )

        quantity_ = quantity(summary, "Consumer lag, broker group total")
        self.assertEqual(quantity_["status"], MEASURED)
        self.assertEqual(quantity_["value"], 1234)

    def test_the_percentiles_come_from_the_histogram_of_the_window(self) -> None:
        summary = self.summarize()

        self.assertEqual(quantity(summary, "Processing latency p50")["value"], 0.5)
        self.assertEqual(quantity(summary, "Processing latency p95")["value"], 2.0)
        self.assertEqual(quantity(summary, "Processing latency p99")["value"], 5.0)
        self.assertIn("histogram_quantile", quantity(summary, "Processing latency p95")["source"])
        self.assertEqual(quantity(summary, "Processing latency, mean")["value"], 0.2)
        self.assertEqual(quantity(summary, "Processing latency, longest step")["value"], 5.0)
        self.assertTrue(summary["latencySource"]["histogram"])

    def test_the_percentiles_fall_back_to_the_gauge_the_broker_does_not_publish(self) -> None:
        final = {
            "latency_p50_gauge_average": 0.0006,
            "latency_p95_gauge_average": 0.0025,
            "latency_p99_gauge_average": 0.006,
        }

        summary = self.summarize(final=final)

        self.assertEqual(quantity(summary, "Processing latency p95")["value"], 2.5)
        self.assertIn(
            "micrometer quantile gauge", quantity(summary, "Processing latency p95")["source"]
        )
        self.assertEqual(summary["latencySource"]["status"], MEASURED)

    def test_a_stack_without_any_latency_source_reports_it_as_unavailable(self) -> None:
        summary = self.summarize(final={})

        for name in ("p50", "p95", "p99"):
            entry = quantity(summary, f"Processing latency {name}")
            self.assertEqual(entry["status"], UNAVAILABLE)
            self.assertIsNone(entry["value"])
            self.assertEqual(benchmark.render_value(entry), UNAVAILABLE)
        self.assertEqual(summary["latencySource"]["status"], UNAVAILABLE)

    def test_cpu_and_memory_are_read_per_container(self) -> None:
        summary = self.summarize()

        self.assertEqual(quantity(summary, "CPU of stream-processor, mean")["value"], 180.0)
        self.assertEqual(quantity(summary, "CPU of event-generator, peak")["value"], 20.0)
        self.assertEqual(quantity(summary, "Memory of event-generator, peak")["value"], 100.0)
        self.assertEqual(quantity(summary, "Memory, highest container")["value"], 900.0)
        self.assertEqual(
            quantity(summary, "Memory, highest container")["note"], "container stream-processor"
        )

    def test_an_empty_window_reports_unavailable_instead_of_zero(self) -> None:
        summary = self.summarize(samples=[sample(0.0, {"generator_published_total": 0.0})])

        produced = quantity(summary, "Events produced")
        self.assertEqual(produced["status"], UNAVAILABLE)
        self.assertEqual(produced["value"], None)
        self.assertEqual(benchmark.render_value(produced), UNAVAILABLE)

    def test_the_run_states_its_own_invariants(self) -> None:
        summary = self.summarize()

        invariant = observation(summary, "the pipeline invariant received = processed + failed")
        self.assertTrue(invariant["held"])
        self.assertEqual(observation(summary, "processing steps per accepted record")["measured"], "3.000")
        # 100 events/s produced against a target of 1,000 is a run that did not sustain it, and
        # the summary says so instead of rounding it away.
        self.assertFalse(observation(summary, "the target rate was sustained")["held"])
        self.assertTrue(observation(summary, "the measured window has the duration of the scenario")["held"])


class ReportTest(unittest.TestCase):
    def test_the_matrix_table_carries_a_number_only_for_a_measured_scenario(self) -> None:
        measured = benchmark.summarize(
            Scenario(1000, 1000, 60.0),
            window_samples(),
            {"latency_p95_histogram": 0.002, "latency_p50_histogram": 0.001, "latency_p99_histogram": 0.005},
            60.0,
            {"event-generator": "one"},
            {},
        )
        not_run = benchmark.not_run_summary(Scenario(5000, 10_000, 300.0), "the stack never started")

        table = benchmark.render_matrix_report([measured, not_run], "test")

        self.assertIn("1000-1000-1m", table)
        # The broker lag of this summary is unavailable - no broker answered in the test - and
        # the cell says so instead of repeating the client metric of the window.
        self.assertIn("| 1000-1000-1m | 100 | 100 | 0 | 1 | 2 | 5 | unavailable | 20 | 100 |", table)
        self.assertIn("5000-10000-5m", table)
        not_run_row = [line for line in table.splitlines() if line.startswith("| 5000-10000-5m")][0]
        self.assertNotIn("0.00", not_run_row)
        # Every column after the name of the scenario states that it did not run.
        self.assertEqual(not_run_row.count(NOT_RUN), 9)

    def test_a_scenario_that_did_not_run_says_so_in_its_report(self) -> None:
        summary = benchmark.not_run_summary(Scenario(20_000, 50_000, 300.0), "docker is not running")
        summary["run"] = {"directory": "results/example"}

        report = benchmark.render_scenario_report(summary, Scenario(20_000, 50_000, 300.0))

        self.assertIn("did not run", report)
        self.assertIn("docker is not running", report)
        self.assertIn(NOT_RUN, report)
        for entry in summary["quantities"]:
            self.assertEqual(entry["status"], NOT_RUN)
            self.assertIsNone(entry["value"])

    def test_a_measured_report_names_the_source_of_every_number(self) -> None:
        summary = benchmark.summarize(
            Scenario(1000, 1000, 60.0),
            window_samples(),
            {"latency_p95_histogram": 0.002, "latency_p50_histogram": 0.001, "latency_p99_histogram": 0.005},
            60.0,
            {"event-generator": "one"},
            {"host": {"cpuModel": "test CPU", "cpuCount": 8, "memoryBytes": 8 * 1024**3}},
        )
        summary["run"] = {"directory": "results/example", "sampleCount": 2}

        report = benchmark.render_scenario_report(summary, Scenario(1000, 1000, 60.0))

        self.assertIn("| Events produced | 100 | events/s | measured |", report)
        self.assertIn("## Raw capture", report)
        self.assertIn("results/example/raw.jsonl", report)


class PlanTest(unittest.TestCase):
    def test_the_environment_of_a_scenario_bounds_the_load(self) -> None:
        environment = benchmark.scenario_environment(Scenario(5000, 10_000, 300.0), 180.0)

        self.assertEqual(environment["BENCH_VEHICLES"], "5000")
        self.assertEqual(environment["BENCH_TARGET_EVENTS_PER_SECOND"], "10000")
        self.assertEqual(environment["BENCH_LOAD_TEST_DURATION"], "8m")

    def test_list_and_dry_run_touch_no_stack(self) -> None:
        for argument in (["--matrix", "--list"], ["--matrix", "--dry-run"]):
            with self.subTest(argument=argument):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    exit_code = benchmark.main(argument)
                self.assertEqual(exit_code, 0)
                self.assertIn("20,000", output.getvalue())

    def test_an_invalid_scenario_stops_before_anything_starts(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as failure:
            benchmark.main(["--scenario", "1000/1000/5x"])
        self.assertEqual(failure.exception.code, 2)

    def test_the_matrix_and_a_scenario_cannot_be_combined(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            benchmark.main(["--matrix", "--scenario", "1000/1000/5m"])

    def test_the_matrix_is_the_one_of_the_roadmap(self) -> None:
        argument = argparse.Namespace(matrix=True, scenario=[], duration="5m")

        matrix = benchmark.scenarios_from(argument)

        self.assertEqual([scenario.name for scenario in matrix], [s.name for s in scenarios.required_matrix()])


class FakePrometheus:
    """A Prometheus that answers with whatever a test tells it to."""

    def __init__(self, answers: dict[str, list[dict] | Exception]) -> None:
        self.answers = answers
        self.asked: list[str] = []

    def query(self, expression: str) -> list[dict]:
        self.asked.append(expression)
        answer = self.answers[expression]
        if isinstance(answer, Exception):
            raise answer
        return answer


class FakeDocker:
    """A Docker that stats nothing, so a sample is only about Prometheus."""

    def stats(self, containers: dict[str, str]) -> dict:
        return {}


def run_context(prometheus: FakePrometheus) -> benchmark.RunContext:
    return benchmark.RunContext(
        scenario=Scenario(1000, 1000, 60.0),
        argument=argparse.Namespace(sample_interval=10.0),
        docker=FakeDocker(),  # type: ignore[arg-type]
        prometheus=prometheus,  # type: ignore[arg-type]
        run_dir=Path("."),
        compose_environment={},
        services=(),
        global_options=[],
    )


class TakeSampleTest(unittest.TestCase):
    """The rule the smoke run of this harness taught it: NaN is not a measurement."""

    def test_a_series_prometheus_cannot_evaluate_is_not_a_measurement(self) -> None:
        prometheus = FakePrometheus(
            {
                "lag": [{"metric": {}, "value": float("nan"), "timestamp": 1.0}],
                "rate": [{"metric": {"a": "1"}, "value": 2.0, "timestamp": 2.0},
                         {"metric": {"a": "2"}, "value": float("nan"), "timestamp": 2.0}],
                "absent": [],
                "broken": RuntimeError("prometheus is down"),
            }
        )

        sample = benchmark.take_sample(
            run_context(prometheus), {"lag": "lag", "rate": "rate", "absent": "absent", "broken": "broken"}, {}
        )

        self.assertIsNone(sample["metrics"]["lag"]["value"])
        self.assertIn("nan", sample["metrics"]["lag"]["note"])
        self.assertEqual(sample["metrics"]["rate"]["value"], 2.0)
        self.assertIn("1 of 2 series", sample["metrics"]["rate"]["note"])
        self.assertIsNone(sample["metrics"]["absent"]["value"])
        self.assertEqual(sample["metrics"]["absent"]["note"], "no series")
        self.assertIsNone(sample["metrics"]["broken"]["value"])
        self.assertIn("prometheus is down", sample["metrics"]["broken"]["error"])


class Answer:
    """The two attributes of a command result that the container sampling reads."""

    def __init__(self, stdout: str, returncode: int = 0) -> None:
        self.stdout = stdout
        self.returncode = returncode
        self.stderr = ""


class ContainerSamplingTest(unittest.TestCase):
    """`docker stats` answers with the short id of a container, and the report asks by service.

    The smoke run of this harness keyed CPU and memory by the name the engine printed, so every
    resource row of its report said `unavailable` while the samples held real numbers. The
    identity of a container is what this test pins.
    """

    GENERATOR = "1f2e3d4c5b6a79887766554433221100aabbccddeeff00112233445566778899"
    PROCESSOR = "99e88776655443322110aabbccddeeff0011223344556677889900aabbccddee"

    def sample_stats(self, payloads: list[dict], containers: dict[str, str]) -> dict:
        """The stats of one `docker stats` answer, with the command itself captured."""
        original = benchmark.run_command
        asked: list[list[str]] = []

        def fake(args: list[str], cwd=None, env=None) -> Answer:
            asked.append(args)
            return Answer("".join(json.dumps(payload) + "\n" for payload in payloads))

        benchmark.run_command = fake  # type: ignore[assignment]
        self.addCleanup(lambda: setattr(benchmark, "run_command", original))
        samples = benchmark.Docker(["compose.yml"], {}).stats(containers)
        self.asked = asked
        return samples

    def test_a_short_id_resolves_to_the_service_that_owns_the_container(self) -> None:
        samples = self.sample_stats(
            [
                {
                    "ID": self.GENERATOR[:12],
                    "Name": "coobi-logistics-event-generator-1",
                    "CPUPerc": "12.50%",
                    "MemUsage": "250MiB / 15.5GiB",
                    "MemPerc": "1.57%",
                },
                {
                    "ID": self.PROCESSOR[:12],
                    "Name": "coobi-logistics-stream-processor-1",
                    "CPUPerc": "180.00%",
                    "MemUsage": "1GiB / 15.5GiB",
                    "MemPerc": "6.45%",
                },
            ],
            {"event-generator": self.GENERATOR, "stream-processor": self.PROCESSOR},
        )

        self.assertEqual(sorted(samples), ["event-generator", "stream-processor"])
        self.assertEqual(samples["event-generator"]["cpu_percent"], 12.5)
        self.assertEqual(samples["event-generator"]["memory_bytes"], 250 * 1024**2)
        self.assertEqual(samples["stream-processor"]["memory_bytes"], 1024**3)
        # The name the engine prints is kept on the row, and the id the run asked about is the
        # full one it collected - nothing here relies on the two having the same length.
        self.assertEqual(
            samples["event-generator"]["container"], "coobi-logistics-event-generator-1"
        )
        self.assertIn(self.GENERATOR, self.asked[0])

    def test_an_entry_of_no_known_container_keeps_the_name_the_engine_reported(self) -> None:
        samples = self.sample_stats(
            [
                {
                    "ID": "0123456789ab",
                    "Name": "some-other-container",
                    "CPUPerc": "3.00%",
                    "MemUsage": "10MiB / 15.5GiB",
                }
            ],
            {"event-generator": self.GENERATOR},
        )

        self.assertEqual(sorted(samples), ["some-other-container"])
        self.assertEqual(samples["some-other-container"]["cpu_percent"], 3.0)


class EnvironmentFactsTest(unittest.TestCase):
    """The two parsers whose input is a container's own output.

    Both were wrong in the first smoke run of the harness for the same reason: the source
    answered, and the shape of the answer was assumed rather than captured. The text below is
    what `java -XshowSettings:vm -version` and `kafka-topics.sh --describe` print.
    """

    def test_the_heap_of_the_jvm_is_read_from_the_line_the_jvm_prints(self) -> None:
        settings = benchmark.parse_java_settings(
            "VM settings:\n"
            "    Max. Heap Size (Estimated): 3.88G\n"
            "    Using VM: OpenJDK 64-Bit Server VM\n"
            "\n"
            'openjdk version "21.0.12" 2026-07-21 LTS\n'
            "OpenJDK Runtime Environment Temurin-21.0.12+8 (build 21.0.12+8-LTS)\n"
        )

        self.assertEqual(settings["status"], MEASURED)
        self.assertEqual(settings["version"], "21.0.12")
        self.assertEqual(settings["maxHeap"], "3.88G")

    def test_an_empty_answer_is_unavailable_rather_than_a_jvm_without_a_heap(self) -> None:
        self.assertEqual(benchmark.parse_java_settings(None)["status"], UNAVAILABLE)
        self.assertEqual(benchmark.parse_java_settings("")["status"], UNAVAILABLE)

    def test_the_partitions_of_the_topic_are_read_from_the_description(self) -> None:
        described = benchmark.parse_topic_description(
            "Topic: logistics.vehicle.location.v1\tTopicId: Kk3M50JhTSKyIaM21t9y4w\t"
            "PartitionCount: 6\tReplicationFactor: 1\tConfigs: min.insync.replicas=1\n"
            "\tTopic: logistics.vehicle.location.v1\tPartition: 0\tLeader: 1\n"
        )

        self.assertEqual(described["status"], MEASURED)
        self.assertEqual(described["partitionCount"], 6)
        self.assertEqual(described["replicationFactor"], 1)
        self.assertEqual(benchmark.parse_topic_description(None)["status"], UNAVAILABLE)


class ComposeOverlayTest(unittest.TestCase):
    """The overlay is the only place a benchmark changes the stack, so it is pinned as text."""

    def setUp(self) -> None:
        self.overlay = (BENCHMARKS / "compose.benchmark.yml").read_text(encoding="utf-8")

    def test_drives_the_generator_in_load_test_mode(self) -> None:
        self.assertIn("GENERATOR_MODE: LOAD_TEST", self.overlay)
        self.assertIn("LOAD_TEST_VEHICLE_COUNT: ${BENCH_VEHICLES", self.overlay)
        self.assertIn(
            "LOAD_TEST_TARGET_EVENTS_PER_SECOND: ${BENCH_TARGET_EVENTS_PER_SECOND", self.overlay
        )
        self.assertIn("LOAD_TEST_DURATION: ${BENCH_LOAD_TEST_DURATION", self.overlay)

    def test_asks_the_processor_for_the_benchmark_profile(self) -> None:
        self.assertIn("SPRING_PROFILES_ACTIVE: benchmark", self.overlay)

    def test_keeps_the_dashboard_out_of_a_benchmark(self) -> None:
        self.assertIn("profiles:", self.overlay)

    def test_changes_nothing_else_of_the_stack(self) -> None:
        services = [
            line.split(":")[0].strip()
            for line in self.overlay.splitlines()
            if line.startswith("  ") and not line.startswith("    ") and line.strip().endswith(":")
        ]
        self.assertEqual(services, ["event-generator", "stream-processor", "frontend"])

    def test_the_json_of_a_run_is_machine_readable(self) -> None:
        summary = benchmark.not_run_summary(Scenario(1000, 1000, 60.0), "not started")

        self.assertEqual(json.loads(json.dumps(summary))["scenario"]["vehicles"], 1000)


if __name__ == "__main__":
    unittest.main()
