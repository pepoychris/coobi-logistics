"""The arithmetic of the MVP-11 benchmark: rates, percentiles and the parsing of samples.

Every function here is pure: it takes the values that were captured against a running stack
and returns what the report states about them. Nothing invents a number for a source that
did not report one - an unreadable counter or an empty histogram comes back as ``None``, and
the reporter turns that into ``unavailable`` rather than into a plausible zero.

The percentile of a histogram follows the algorithm of Prometheus' ``histogram_quantile()``
itself, so a rate computed here and the same quantile computed by a Prometheus query agree.
"""

from __future__ import annotations

import re
from typing import Iterable, Sequence

_DOCKER_SIZE_PATTERN = re.compile(r"^(?P<value>\d+(?:\.\d+)?)\s*(?P<unit>[kKmMgGtTpP]?i?[bB]?)$")

_BINARY_UNITS = {
    "": 1.0,
    "b": 1.0,
    "kb": 1024.0,
    "kib": 1024.0,
    "mb": 1024.0**2,
    "mib": 1024.0**2,
    "gb": 1024.0**3,
    "gib": 1024.0**3,
    "tb": 1024.0**4,
    "tib": 1024.0**4,
    "pb": 1024.0**5,
    "pib": 1024.0**5,
}


def counter_delta(samples: Sequence[tuple[float, float]]) -> float | None:
    """Growth of a monotonic counter over a series of ``(timestamp, value)`` samples.

    ``None`` when there is nothing to compare, and also when the counter went backwards: a
    restart of the process that owns it is not a negative number of events, and reporting one
    would be worse than reporting that the series cannot be read as a rate.
    """
    points = _clean(samples)
    if len(points) < 2:
        return None
    first, last = points[0], points[-1]
    if last[1] < first[1]:
        return None
    return last[1] - first[1]


def sustained_rate(samples: Sequence[tuple[float, float]]) -> float | None:
    """Events per second sustained by a monotonic counter over the sampled window.

    The rate is measured between the first and the last sample, not averaged from per-sample
    differences, so a slow sample in the middle of a window cannot weigh more than the window
    itself. ``None`` when the window is empty, shorter than a millisecond, or holds a counter
    that went backwards.
    """
    points = _clean(samples)
    if len(points) < 2:
        return None
    first, last = points[0], points[-1]
    elapsed = last[0] - first[0]
    if elapsed <= 0:
        return None
    delta = counter_delta(points)
    if delta is None:
        return None
    return delta / elapsed


def percentile(values: Sequence[float], quantile: float) -> float | None:
    """Linear-interpolation percentile of a series of observations.

    This is the definition the report uses for a series it collected itself, for example the
    CPU percentages of a container across the samples of a window. A percentile of the
    *processing latency* is not computed from a series at all: those observations live inside
    the stream processor, so the report reads them from its histogram instead.
    """
    if not values:
        return None
    if not 0 <= quantile <= 1:
        raise ValueError(f"quantile must be between 0 and 1 but was {quantile}")
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = quantile * (len(ordered) - 1)
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def histogram_quantile(
    quantile: float, buckets: Sequence[tuple[float | None, float]]
) -> float | None:
    """Percentile of a cumulative histogram of ``(upper bound, cumulative count)`` buckets.

    The buckets are the ones of a Prometheus histogram: counts are cumulative, the bounds are
    ascending, and the last one may be open (``None``, the ``+Inf`` bucket). The result is
    interpolated inside the bucket that holds the rank, exactly as ``histogram_quantile()``
    does, so a value computed here and the same value computed by Prometheus agree.

    ``None`` when the histogram has fewer than two buckets or holds no observation, because a
    percentile of nothing is not a latency.
    """
    if not 0 <= quantile <= 1:
        raise ValueError(f"quantile must be between 0 and 1 but was {quantile}")
    ordered = _monotonic([(bound, count) for bound, count in buckets])
    if len(ordered) < 2:
        return None
    observations = ordered[-1][1]
    if observations <= 0:
        return None
    rank = quantile * observations
    # A histogram of Prometheus ends in the open (`+Inf`) bucket, and the quantile can never be
    # located by it: a rank inside it is reported as the highest finite bound, which is the
    # same fallback Prometheus uses.
    closed = ordered[:-1] if ordered[-1][0] is None else ordered
    index = None
    for position, (_, count) in enumerate(closed):
        if count >= rank:
            index = position
            break
    if index is None:
        return closed[-1][0]
    upper = closed[index][0]
    if upper is None:
        return None
    lower_bound = closed[index - 1][0] if index > 0 else 0.0
    if index == 0 and upper <= 0:
        return upper
    count_in_bucket = closed[index][1] - (closed[index - 1][1] if index > 0 else 0.0)
    if count_in_bucket <= 0:
        return lower_bound
    rank_in_bucket = rank - (closed[index - 1][1] if index > 0 else 0.0)
    return lower_bound + (upper - lower_bound) * (rank_in_bucket / count_in_bucket)


def parse_prometheus_buckets(series: Iterable[dict]) -> list[tuple[float | None, float]]:
    """Turn the vector of a Prometheus ``_bucket`` query into buckets of ``metrics``.

    A series without an ``le`` label, or a scraped value that is not a number, is skipped: it
    is not a bucket of the histogram, and guessing one would fabricate a distribution.
    """
    buckets: list[tuple[float | None, float]] = []
    for sample in series:
        bound = sample.get("le")
        value = sample.get("value")
        if bound is None or value is None:
            continue
        if str(bound).strip() in ("+Inf", "Inf", "inf"):
            buckets.append((None, float(value)))
            continue
        try:
            buckets.append((float(bound), float(value)))
        except (TypeError, ValueError):
            continue
    return buckets


def parse_size(text: str) -> float | None:
    """Bytes of a Docker size such as ``123.4MiB``, ``1.5GiB`` or ``0B``.

    Docker reports these units in binary multiples, which is what the conversion here uses.
    """
    if not isinstance(text, str):
        return None
    match = _DOCKER_SIZE_PATTERN.match(text.strip())
    if match is None:
        return None
    unit = match.group("unit").lower()
    factor = _BINARY_UNITS.get(unit)
    if factor is None:
        return None
    return float(match.group("value")) * factor


def parse_percentage(text: str) -> float | None:
    """Percentage of a Docker field such as ``12.34%``."""
    if not isinstance(text, str):
        return None
    cleaned = text.strip().rstrip("%")
    try:
        return float(cleaned)
    except ValueError:
        return None


def parse_docker_stats(payload: dict) -> dict | None:
    """The two numbers a benchmark keeps from one ``docker stats`` entry: CPU and resident memory.

    ``{ "used": "123.4MiB / 7.6GiB" }`` describes a container that is not there yet, and a
    sample of it is not a measurement of anything, so it is skipped instead of being read as
    zero CPU.
    """
    name = payload.get("Name") or payload.get("Container")
    cpu = parse_percentage(payload.get("CPUPerc", ""))
    used = parse_size(str(payload.get("MemUsage", "")).split("/")[0])
    limit = parse_size(str(payload.get("MemUsage", "")).split("/")[-1])
    if name is None or cpu is None or used is None:
        return None
    return {
        "container": str(name),
        "cpu_percent": cpu,
        "memory_bytes": used,
        "memory_limit_bytes": limit,
        "memory_percent": parse_percentage(payload.get("MemPerc", "")),
    }


def _clean(samples: Sequence[tuple[float, float]]) -> list[tuple[float, float]]:
    """Sort by timestamp and drop the points a source did not report.

    A ``NaN`` or an infinity is one of those points: Prometheus answers with one when it cannot
    evaluate a rate, and a series that holds one is not a series a rate can be read from.
    """
    cleaned = []
    for timestamp, value in samples:
        if timestamp is None or value is None:
            continue
        point = (float(timestamp), float(value))
        if point[0] != point[0] or point[1] != point[1]:
            continue
        if point[0] in (float("inf"), float("-inf")) or point[1] in (float("inf"), float("-inf")):
            continue
        cleaned.append(point)
    cleaned.sort(key=lambda point: point[0])
    return cleaned


def _monotonic(buckets: Sequence[tuple[float | None, float]]) -> list[tuple[float | None, float]]:
    """Sort the buckets by bound and make their counts cumulative, as Prometheus does."""
    ordered = sorted(
        ((float("inf") if bound is None else float(bound), count) for bound, count in buckets),
        key=lambda bucket: bucket[0],
    )
    cumulative: list[tuple[float | None, float]] = []
    highest = 0.0
    for bound, count in ordered:
        highest = max(highest, count)
        cumulative.append((None if bound == float("inf") else bound, highest))
    return cumulative
