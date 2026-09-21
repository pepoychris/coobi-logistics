/**
 * How a vehicle of the map is said in words.
 *
 * The map draws a shape; the panel around it has to be readable, and assistive
 * technology has to be told the same thing. So the words are built here, from
 * the values the contract reports, by the same formatters the rest of the
 * dashboard uses - including the rule of MVP-5.4 that a value which is absent
 * is a visible gap and never a zero.
 */

import type { Vehicle, VehicleStatus } from '../api/types'
import { formatClock, formatSpeedKph, UNAVAILABLE } from '../util/format'

/** Counts are grouped, because a fleet of thousands is read at a glance. */
const COUNT = new Intl.NumberFormat('en-US')

/** One labelled value of the panel that describes a vehicle. */
export interface VehicleDetailRow {
  label: string
  value: string
  /** Where the value comes from, shown to a reader who wants to check it. */
  hint: string
}

/** Where a heading points, in words. */
const COMPASS_POINTS = ['north', 'north-east', 'east', 'south-east', 'south', 'south-west', 'west', 'north-west'] as const

/**
 * @param status movement status of the contract
 * @returns the same status as a word a reader sees
 */
export function statusWord(status: VehicleStatus): string {
  return status === 'MOVING' ? 'Moving' : 'Stopped'
}

/**
 * @param heading degrees clockwise from north
 * @returns the compass point that heading points at
 */
export function compassPoint(heading: number | null | undefined): string {
  if (typeof heading !== 'number' || !Number.isFinite(heading)) {
    return UNAVAILABLE
  }
  const normalized = ((heading % 360) + 360) % 360
  const point = COMPASS_POINTS[Math.round(normalized / 45) % COMPASS_POINTS.length]
  return point ?? UNAVAILABLE
}

/**
 * The values of one vehicle, ready to be listed.
 *
 * @param vehicle latest state of the vehicle
 * @returns one row per value the dashboard shows for it
 */
export function vehicleDetailRows(vehicle: Vehicle): VehicleDetailRow[] {
  const headingDegrees = Number.isFinite(vehicle.heading) ? `${Math.round(vehicle.heading)}°` : UNAVAILABLE
  return [
    {
      label: 'Vehicle',
      value: vehicle.vehicleId,
      hint: 'Identifier the telemetry contract keys every event by',
    },
    {
      label: 'Status',
      value: statusWord(vehicle.status),
      hint: 'Movement status of the latest telemetry event',
    },
    {
      label: 'Speed',
      value: formatSpeedKph(vehicle.speed),
      hint: 'Speed reported by the latest telemetry event',
    },
    {
      label: 'Heading',
      value: `${compassPoint(vehicle.heading)} (${headingDegrees})`,
      hint: 'Degrees clockwise from north',
    },
    {
      label: 'Last telemetry',
      value: formatClock(vehicle.lastUpdate),
      hint: 'Instant of the latest telemetry event, in local time',
    },
    {
      label: 'Read model',
      value: formatClock(vehicle.updatedAt),
      hint: 'Instant the stored state of the vehicle was last written',
    },
  ]
}

/**
 * One line on how much of the fleet the map is drawing.
 *
 * The two numbers are the point: what is drawn, and what is only acknowledged
 * as a faint cloud. Saying it is what keeps the budget from looking like the
 * fleet and the differences between them unexplained.
 *
 * @param counts what the split of the fleet produced
 * @returns the sentence the panel shows
 */
export function fleetBudgetSummary(counts: {
  tracked: number
  rendered: number
  background: number
  omitted: number
}): string {
  if (counts.tracked === 0) {
    return 'No vehicle state has arrived yet'
  }
  const parts = [`${COUNT.format(counts.rendered)} of ${COUNT.format(counts.tracked)} vehicles drawn in full`]
  if (counts.background > 0) {
    parts.push(`${COUNT.format(counts.background)} faint in the background`)
  }
  if (counts.omitted > 0) {
    parts.push(`${COUNT.format(counts.omitted)} beyond the cloud budget`)
  }
  return parts.join(' · ')
}
