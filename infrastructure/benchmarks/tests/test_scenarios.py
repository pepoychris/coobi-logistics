"""Deterministic tests of the scenario model and of the labels of a report."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scenarios  # noqa: E402
from scenarios import (  # noqa: E402
    MEASURED,
    NOT_RUN,
    UNAVAILABLE,
    BenchmarkInputError,
    Quantity,
    Scenario,
    format_duration,
    format_number,
    parse_duration,
    parse_scenario,
    required_matrix,
    scale_matrix,
)


class ParseDurationTest(unittest.TestCase):
    def test_parses_the_documented_units(self) -> None:
        self.assertEqual(parse_duration("5m"), 300.0)
        self.assertEqual(parse_duration("90s"), 90.0)
        self.assertEqual(parse_duration("2h"), 7200.0)
        self.assertEqual(parse_duration("1500ms"), 1.5)
        self.assertEqual(parse_duration("0.5m"), 30.0)
        self.assertEqual(parse_duration(" 10s "), 10.0)

    def test_rejects_a_duration_without_a_unit(self) -> None:
        for text in ("5", "5 minutes", "", "abc", "5s5s", "-5m"):
            with self.subTest(text=text), self.assertRaises(BenchmarkInputError):
                parse_duration(text)

    def test_rejects_a_duration_that_is_not_positive(self) -> None:
        with self.assertRaises(BenchmarkInputError):
            parse_duration("0s")


class FormatDurationTest(unittest.TestCase):
    def test_keeps_the_unit_a_person_would_have_asked_for(self) -> None:
        self.assertEqual(format_duration(300.0), "5m")
        self.assertEqual(format_duration(7200.0), "2h")
        self.assertEqual(format_duration(90.0), "90s")
        self.assertEqual(format_duration(20.0), "20s")
        self.assertEqual(format_duration(1.5), "1.5s")
        self.assertEqual(format_duration(0.5), "500ms")


class ScenarioTest(unittest.TestCase):
    def test_parses_a_scenario_of_the_command_line(self) -> None:
        scenario = parse_scenario("1000/1000/5m")

        self.assertEqual(scenario.vehicles, 1000)
        self.assertEqual(scenario.target_events_per_second, 1000)
        self.assertEqual(scenario.duration_seconds, 300.0)
        self.assertEqual(scenario.duration, "5m")
        self.assertEqual(scenario.name, "1000-1000-5m")

    def test_accepts_the_thousands_separators_of_the_documentation(self) -> None:
        scenario = parse_scenario("5,000/10,000/5m")

        self.assertEqual(scenario.vehicles, 5000)
        self.assertEqual(scenario.target_events_per_second, 10_000)

    def test_rejects_a_scenario_that_is_not_three_numbers(self) -> None:
        for text in ("1000/1000", "1000/1000/5m/extra", "1000//5m", ""):
            with self.subTest(text=text), self.assertRaises(BenchmarkInputError):
                parse_scenario(text)

    def test_rejects_a_rate_or_a_fleet_that_is_not_a_number(self) -> None:
        with self.assertRaises(BenchmarkInputError):
            parse_scenario("many/1000/5m")
        with self.assertRaises(BenchmarkInputError):
            parse_scenario("1000/1 000/5m")

    def test_rejects_out_of_range_values(self) -> None:
        for vehicles, target, duration in (
            (0, 1000, 300.0),
            (-1, 1000, 300.0),
            (1000, 0, 300.0),
            (1000, 1000, 0.0),
            (1_000_001, 1000, 300.0),
            (1000, 2_000_001, 300.0),
        ):
            with self.subTest(vehicles=vehicles, target=target, duration=duration), self.assertRaises(
                BenchmarkInputError
            ):
                Scenario(vehicles, target, duration)

    def test_the_required_matrix_is_the_one_of_the_roadmap(self) -> None:
        matrix = required_matrix()

        self.assertEqual(
            [(scenario.vehicles, scenario.target_events_per_second) for scenario in matrix],
            [(1000, 1000), (5000, 10_000), (10_000, 25_000), (20_000, 50_000)],
        )
        self.assertTrue(all(scenario.duration == "5m" for scenario in matrix))

    def test_the_matrix_can_be_measured_over_a_shorter_window(self) -> None:
        matrix = scale_matrix(60.0)

        self.assertEqual([scenario.vehicles for scenario in matrix], [1000, 5000, 10_000, 20_000])
        # Sixty seconds is one minute, and the name of a run says so.
        self.assertTrue(all(scenario.duration == "1m" for scenario in matrix))


class QuantityTest(unittest.TestCase):
    def test_a_measurement_without_a_value_is_unavailable(self) -> None:
        quantity = scenarios.measured("Events produced", "events/s", None, "prometheus")

        self.assertEqual(quantity.status, UNAVAILABLE)
        self.assertEqual(quantity.render(), UNAVAILABLE)

    def test_a_measured_quantity_renders_its_number(self) -> None:
        quantity = scenarios.measured("Events produced", "events/s", 9999.6, "prometheus")

        self.assertEqual(quantity.status, MEASURED)
        self.assertEqual(quantity.render(), "9,999.60")

    def test_a_measured_quantity_must_carry_a_value(self) -> None:
        with self.assertRaises(BenchmarkInputError):
            Quantity("Events produced", "events/s", None, MEASURED, "prometheus")

    def test_an_unavailable_quantity_must_not_carry_a_value(self) -> None:
        with self.assertRaises(BenchmarkInputError):
            Quantity("Events produced", "events/s", 1.0, UNAVAILABLE, "prometheus")

    def test_an_unknown_status_is_rejected(self) -> None:
        with self.assertRaises(BenchmarkInputError):
            Quantity("Events produced", "events/s", 1.0, "estimated", "prometheus")

    def test_a_scenario_that_did_not_run_states_it(self) -> None:
        quantity = scenarios.not_run("Events produced", "events/s", "prometheus")

        self.assertEqual(quantity.render(), NOT_RUN)
        self.assertIsNone(quantity.value)


class FormatNumberTest(unittest.TestCase):
    def test_formats_counts_and_measurements(self) -> None:
        self.assertEqual(format_number(1234), "1,234")
        self.assertEqual(format_number(1234.5), "1,234.50")
        self.assertEqual(format_number(0.005), "0.01")
        self.assertEqual(format_number(None), UNAVAILABLE)


class SeriesTest(unittest.TestCase):
    def test_mean_and_maximum_of_an_empty_series(self) -> None:
        self.assertIsNone(scenarios.mean([]))
        self.assertIsNone(scenarios.maximum([]))

    def test_mean_and_maximum_of_a_series(self) -> None:
        self.assertEqual(scenarios.mean([1.0, 2.0, 3.0]), 2.0)
        self.assertEqual(scenarios.maximum([1.0, 5.0, 3.0]), 5.0)


if __name__ == "__main__":
    unittest.main()
