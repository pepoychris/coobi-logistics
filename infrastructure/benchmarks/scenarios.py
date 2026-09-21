"""Scenarios and labelled quantities of the MVP-11 benchmark.

This module is the part of the harness that decides what a benchmark run *is* and how a
measured number is presented. It holds no Docker, no HTTP and no clock, so the parsing, the
validation and the arithmetic of a report can be tested without a stack, and the runner and
the reporter use the same definition of a scenario.

The one rule the whole harness follows lives here: a quantity carries the status of its
source. ``measured`` carries a number that was read from something; ``unavailable`` and
``not run`` carry no number at all, and the report prints the status instead of a value, so a
missing measurement can never be read as a performance result.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable, Sequence

# Status of a quantity.
MEASURED = "measured"
UNAVAILABLE = "unavailable"
NOT_RUN = "not run"

_DURATION_PATTERN = re.compile(r"^(?P<value>\d+(?:\.\d+)?)(?P<unit>ms|s|m|h)$")
_DURATION_UNITS = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0}

_MAX_VEHICLES = 1_000_000
_MAX_EVENTS_PER_SECOND = 2_000_000


class BenchmarkInputError(ValueError):
    """An argument that cannot be turned into a scenario."""


def parse_duration(text: str) -> float:
    """Parse ``5m``, ``300s``, ``90s`` or ``2h`` into seconds.

    The accepted shapes are the ones the harness documents, not the ones Spring accepts:
    a benchmark window is read by a person, and ``5 minutes`` or a bare ``5`` would be an
    ambiguity about the unit rather than a shortcut.
    """
    if not isinstance(text, str):
        raise BenchmarkInputError(f"duration must be text but was {type(text).__name__}")
    match = _DURATION_PATTERN.match(text.strip())
    if match is None:
        raise BenchmarkInputError(
            f"duration '{text}' is not a duration; use a value and a unit, for example 5m, 300s or 2h"
        )
    seconds = float(match.group("value")) * _DURATION_UNITS[match.group("unit")]
    if seconds <= 0:
        raise BenchmarkInputError(f"duration '{text}' must be greater than zero")
    return seconds


def format_duration(seconds: float) -> str:
    """Render seconds as the shortest exact form of the documented units.

    A whole number of hours or minutes is written in that unit, and anything else stays in
    seconds, so the name of a run reads the way the run was requested: ``5m`` for 300 seconds
    and ``90s`` for ninety of them.
    """
    if seconds <= 0:
        return "0s"
    for unit, factor in (("h", 3600.0), ("m", 60.0)):
        if seconds >= factor and float(seconds / factor).is_integer():
            return f"{seconds / factor:g}{unit}"
    if seconds < 1:
        return f"{seconds * 1000:g}ms"
    return f"{seconds:g}s"


@dataclass(frozen=True)
class Scenario:
    """One benchmark run: a fleet, a target rate and a measured window."""

    vehicles: int
    target_events_per_second: int
    duration_seconds: float

    def __post_init__(self) -> None:
        if not isinstance(self.vehicles, int) or isinstance(self.vehicles, bool):
            raise BenchmarkInputError(f"vehicles must be a whole number but was '{self.vehicles}'")
        if not 1 <= self.vehicles <= _MAX_VEHICLES:
            raise BenchmarkInputError(
                f"vehicles must be between 1 and {_MAX_VEHICLES} but was {self.vehicles}"
            )
        if not isinstance(self.target_events_per_second, int) or isinstance(
            self.target_events_per_second, bool
        ):
            raise BenchmarkInputError(
                f"target events per second must be a whole number but was '{self.target_events_per_second}'"
            )
        if not 1 <= self.target_events_per_second <= _MAX_EVENTS_PER_SECOND:
            raise BenchmarkInputError(
                "target events per second must be between 1 and "
                f"{_MAX_EVENTS_PER_SECOND} but was {self.target_events_per_second}"
            )
        if not isinstance(self.duration_seconds, (int, float)) or isinstance(
            self.duration_seconds, bool
        ):
            raise BenchmarkInputError(
                f"duration must be a number of seconds but was '{self.duration_seconds}'"
            )
        if self.duration_seconds <= 0:
            raise BenchmarkInputError(
                f"duration must be greater than zero but was {self.duration_seconds}"
            )

    @property
    def duration(self) -> str:
        """The window as it is written on the command line."""
        return format_duration(self.duration_seconds)

    @property
    def name(self) -> str:
        """Directory-safe name of the run, unique per scenario."""
        return f"{self.vehicles}-{self.target_events_per_second}-{self.duration}"

    def __str__(self) -> str:
        return (
            f"{self.vehicles:,} vehicles at {self.target_events_per_second:,} events/s "
            f"for {self.duration}"
        )


def parse_scenario(text: str) -> Scenario:
    """Parse ``<vehicles>/<target events per second>/<duration>``, for example ``1000/1000/5m``."""
    if not isinstance(text, str):
        raise BenchmarkInputError(f"scenario must be text but was {type(text).__name__}")
    parts = [part.strip() for part in text.strip().split("/")]
    if len(parts) != 3:
        raise BenchmarkInputError(
            f"scenario '{text}' must be '<vehicles>/<events per second>/<duration>', for example 1000/1000/5m"
        )
    vehicles, target, duration = parts
    return Scenario(
        vehicles=parse_whole_number(vehicles, "vehicles"),
        target_events_per_second=parse_whole_number(target, "target events per second"),
        duration_seconds=parse_duration(duration),
    )


def parse_whole_number(text: str, what: str) -> int:
    """Parse a count that a scenario states for a person to read, thousands separator included."""
    cleaned = text.replace("_", "").replace(",", "").strip()
    try:
        return int(cleaned)
    except ValueError as invalid:
        raise BenchmarkInputError(f"{what} must be a whole number but was '{text}'") from invalid


def required_matrix() -> tuple[Scenario, ...]:
    """The four scenarios MVP-11.2 tests at minimum, all of them five minutes long."""
    return tuple(
        Scenario(vehicles, target, 300.0)
        for vehicles, target in ((1_000, 1_000), (5_000, 10_000), (10_000, 25_000), (20_000, 50_000))
    )


def scale_matrix(duration_seconds: float) -> tuple[Scenario, ...]:
    """The four required fleets and rates, measured over a different window."""
    return tuple(
        Scenario(scenario.vehicles, scenario.target_events_per_second, duration_seconds)
        for scenario in required_matrix()
    )


@dataclass(frozen=True)
class Quantity:
    """One cell of the report: what was measured, how much of it, and where it came from."""

    metric: str
    unit: str
    value: float | int | None
    status: str
    source: str
    note: str = ""

    def __post_init__(self) -> None:
        if self.status not in (MEASURED, UNAVAILABLE, NOT_RUN):
            raise BenchmarkInputError(f"unknown status '{self.status}' of {self.metric}")
        if self.status == MEASURED and self.value is None:
            raise BenchmarkInputError(f"{self.metric} is measured but carries no value")
        if self.status != MEASURED and self.value is not None:
            raise BenchmarkInputError(f"{self.metric} is {self.status} but carries a value")

    @property
    def is_measured(self) -> bool:
        return self.status == MEASURED

    def render(self, digits: int = 2) -> str:
        """The cell as the report prints it: a number only when there is a measurement."""
        if not self.is_measured:
            return self.status
        return format_number(self.value, digits)

    def as_json(self) -> dict:
        return {
            "metric": self.metric,
            "unit": self.unit,
            "value": self.value,
            "status": self.status,
            "source": self.source,
            "note": self.note,
        }


def measured(
    metric: str, unit: str, value: float | int | None, source: str, note: str = ""
) -> Quantity:
    """A quantity that was read from a source, or that the source did not report."""
    if value is None:
        return Quantity(metric, unit, None, UNAVAILABLE, source, note)
    return Quantity(metric, unit, value, MEASURED, source, note)


def unavailable(metric: str, unit: str, source: str, note: str = "") -> Quantity:
    """A quantity whose source does not expose it on this stack."""
    return Quantity(metric, unit, None, UNAVAILABLE, source, note)


def not_run(metric: str, unit: str, source: str, note: str = "") -> Quantity:
    """A quantity of a run that did not happen."""
    return Quantity(metric, unit, None, NOT_RUN, source, note)


def format_number(value: float | int | None, digits: int = 2) -> str:
    """Format a number the way the report tables read: thousands separated, bounded decimals."""
    if value is None:
        return UNAVAILABLE
    if isinstance(value, bool):
        raise BenchmarkInputError(f"a boolean is not a measurement but was {value}")
    if isinstance(value, int) or (isinstance(value, float) and value.is_integer()):
        return f"{int(value):,}"
    return f"{value:,.{digits}f}"


def mean(values: Sequence[float]) -> float | None:
    """Arithmetic mean of a series, or ``None`` for an empty one."""
    if not values:
        return None
    return sum(values) / len(values)


def maximum(values: Iterable[float]) -> float | None:
    """Largest value of a series, or ``None`` for an empty one."""
    series = list(values)
    if not series:
        return None
    return max(series)
