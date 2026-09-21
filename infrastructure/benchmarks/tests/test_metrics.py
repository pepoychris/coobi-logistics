"""Deterministic tests of the rates, the percentiles and the sample parsing of the harness."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from metrics import (  # noqa: E402
    counter_delta,
    histogram_quantile,
    parse_docker_stats,
    parse_percentage,
    parse_prometheus_buckets,
    parse_size,
    percentile,
    sustained_rate,
)


class CounterTest(unittest.TestCase):
    def test_rate_between_the_first_and_the_last_sample(self) -> None:
        samples = [(0.0, 0.0), (30.0, 3000.0), (60.0, 6000.0)]

        self.assertEqual(counter_delta(samples), 6000.0)
        self.assertEqual(sustained_rate(samples), 100.0)

    def test_the_order_of_the_samples_does_not_matter(self) -> None:
        samples = [(60.0, 6000.0), (0.0, 0.0)]

        self.assertEqual(sustained_rate(samples), 100.0)

    def test_a_single_sample_is_not_a_rate(self) -> None:
        self.assertIsNone(counter_delta([(0.0, 10.0)]))
        self.assertIsNone(sustained_rate([(0.0, 10.0)]))
        self.assertIsNone(sustained_rate([]))

    def test_a_counter_that_went_backwards_is_not_a_rate(self) -> None:
        samples = [(0.0, 1000.0), (60.0, 20.0)]

        self.assertIsNone(counter_delta(samples))
        self.assertIsNone(sustained_rate(samples))

    def test_samples_a_source_did_not_report_are_dropped(self) -> None:
        samples = [(0.0, 0.0), (30.0, None), (60.0, 600.0)]

        self.assertEqual(sustained_rate(samples), 10.0)

    def test_a_window_of_no_duration_is_not_a_rate(self) -> None:
        self.assertIsNone(sustained_rate([(10.0, 5.0), (10.0, 50.0)]))


class PercentileTest(unittest.TestCase):
    def test_percentiles_of_a_known_series(self) -> None:
        values = [float(value) for value in range(1, 11)]

        self.assertEqual(percentile(values, 0.0), 1.0)
        self.assertEqual(percentile(values, 0.5), 5.5)
        self.assertAlmostEqual(percentile(values, 0.95), 9.55)
        self.assertEqual(percentile(values, 1.0), 10.0)

    def test_percentile_of_an_empty_series_is_unavailable(self) -> None:
        self.assertIsNone(percentile([], 0.95))

    def test_percentile_of_one_observation_is_that_observation(self) -> None:
        self.assertEqual(percentile([7.0], 0.5), 7.0)
        self.assertEqual(percentile([7.0], 0.99), 7.0)

    def test_a_quantile_outside_zero_and_one_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            percentile([1.0], 1.5)


class HistogramQuantileTest(unittest.TestCase):
    """The expected values are the ones ``histogram_quantile()`` of Prometheus returns."""

    def test_a_rank_inside_the_first_bucket_interpolates_from_zero(self) -> None:
        buckets = [(1.0, 10.0), (2.0, 20.0)]

        self.assertEqual(histogram_quantile(0.5, buckets), 1.0)

    def test_a_rank_inside_a_later_bucket_interpolates_between_its_bounds(self) -> None:
        buckets = [(1.0, 10.0), (2.0, 20.0)]

        self.assertEqual(histogram_quantile(0.75, buckets), 1.5)

    def test_the_open_bucket_contributes_its_observations_to_the_total(self) -> None:
        buckets = [(1.0, 10.0), (2.0, 20.0), (None, 40.0)]

        # 15 of 40 observations fall in 1..2, and the rest are above the highest bound, so the
        # quantile is inside the closed part of the distribution.
        self.assertAlmostEqual(histogram_quantile(0.375, buckets), 1.5)

    def test_a_rank_above_every_closed_bucket_is_reported_as_the_highest_bound(self) -> None:
        buckets = [(1.0, 5.0), (2.0, 6.0), (None, 10.0)]

        self.assertEqual(histogram_quantile(1.0, buckets), 2.0)

    def test_buckets_are_read_in_order_and_their_counts_made_cumulative(self) -> None:
        out_of_order = [(2.0, 20.0), (1.0, 10.0), (None, 20.0)]

        self.assertEqual(histogram_quantile(0.5, out_of_order), 1.0)

    def test_a_histogram_without_observations_is_unavailable(self) -> None:
        self.assertIsNone(histogram_quantile(0.95, [(1.0, 0.0), (None, 0.0)]))
        self.assertIsNone(histogram_quantile(0.95, []))
        self.assertIsNone(histogram_quantile(0.95, [(1.0, 5.0)]))

    def test_a_quantile_outside_zero_and_one_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            histogram_quantile(-0.1, [(1.0, 1.0), (None, 1.0)])

    def test_buckets_are_read_from_the_vector_of_a_prometheus_query(self) -> None:
        series = [
            {"le": "0.001", "value": "10"},
            {"le": "0.002", "value": "20"},
            {"le": "+Inf", "value": "25"},
            {"le": "0.0005", "value": "not-a-number"},
            {"value": "1"},
        ]

        self.assertEqual(
            parse_prometheus_buckets(series), [(0.001, 10.0), (0.002, 20.0), (None, 25.0)]
        )

    def test_the_percentiles_of_a_latency_distribution_are_the_interpolated_ones(self) -> None:
        # 1,000 processing steps: 900 below 0.2 ms, 90 between 0.2 and 1 ms and 10 below 5 ms.
        # The expectations are hand-computed with the formula of histogram_quantile, so this
        # test pins the arithmetic instead of pinning whatever the code happens to do.
        buckets = [(0.0002, 900.0), (0.001, 990.0), (0.005, 1000.0), (None, 1000.0)]

        # p50: rank 500 of the 900 observations of the first bucket, from 0 to 0.2 ms.
        self.assertAlmostEqual(histogram_quantile(0.5, buckets), 0.0002 * 500 / 900, places=9)
        # p90: rank 900 is the last observation of the first bucket, so its upper bound.
        self.assertAlmostEqual(histogram_quantile(0.9, buckets), 0.0002, places=9)
        # p99: rank 990 is the last observation of the second bucket, from 0.2 to 1 ms.
        self.assertAlmostEqual(histogram_quantile(0.99, buckets), 0.001, places=9)


class DockerFieldTest(unittest.TestCase):
    def test_parses_the_binary_units_docker_reports(self) -> None:
        self.assertEqual(parse_size("0B"), 0.0)
        self.assertEqual(parse_size("1KiB"), 1024.0)
        self.assertEqual(parse_size("1.5GiB"), 1.5 * 1024**3)
        self.assertAlmostEqual(parse_size("123.4MiB"), 123.4 * 1024**2)

    def test_a_size_that_is_not_a_size_is_unavailable(self) -> None:
        for text in ("--", "", "lots", "12 furlongs"):
            with self.subTest(text=text):
                self.assertIsNone(parse_size(text))

    def test_parses_a_percentage(self) -> None:
        self.assertEqual(parse_percentage("12.34%"), 12.34)
        self.assertIsNone(parse_percentage("--"))

    def test_parses_a_docker_stats_entry(self) -> None:
        sample = parse_docker_stats(
            {
                "Name": "coobi-logistics-stream-processor-1",
                "CPUPerc": "182.50%",
                "MemUsage": "512.5MiB / 7.6GiB",
                "MemPerc": "6.58%",
            }
        )

        self.assertIsNotNone(sample)
        assert sample is not None
        self.assertEqual(sample["cpu_percent"], 182.5)
        self.assertAlmostEqual(sample["memory_bytes"], 512.5 * 1024**2)

    def test_a_container_that_is_not_ready_is_not_a_sample(self) -> None:
        self.assertIsNone(
            parse_docker_stats({"Name": "coobi-1", "CPUPerc": "--", "MemUsage": "-- / --"})
        )

    def test_a_sample_that_is_not_a_number_is_dropped(self) -> None:
        # Prometheus answers with NaN when it cannot evaluate a rate, and NaN is not a rate.
        self.assertIsNone(sustained_rate([(0.0, 0.0), (60.0, float("nan"))]))
        self.assertEqual(
            sustained_rate([(0.0, 0.0), (30.0, float("inf")), (60.0, 600.0)]), 10.0
        )


if __name__ == "__main__":
    unittest.main()
