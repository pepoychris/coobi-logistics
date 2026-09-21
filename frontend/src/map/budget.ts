/**
 * What the map draws in full, and what it merely acknowledges.
 *
 * A browser that created one mesh per vehicle would stop being usable long
 * before a fleet of a few thousand is a fleet of a few thousand. So the fleet
 * is split in two: at most {@link MAX_RENDERED_VEHICLES} vehicles are drawn as
 * their own mini vehicle, and every other vehicle of the fleet is handed to a
 * single faint cloud of points, which costs one draw call no matter how many
 * vehicles are in it.
 *
 * The split is a pure function of the fleet and the budget, and it is stable:
 * the same fleet always splits the same way, on any machine, and a vehicle does
 * not jump between the two halves just because a tick arrived. Two rules decide
 * who is drawn:
 *
 * - the vehicle a reader selected is always one of them, wherever it is in the
 *   fleet, because a selection that vanished would be a bug to the reader;
 * - the rest of the budget keeps the mix of the fleet. A fleet that is half
 *   parked draws half parked vehicles, so a movement state is never hidden by
 *   the budget - which matters, because a map that drew only the moving
 *   vehicles while the panel said forty were stopped would be telling two
 *   different stories.
 *
 * Within a state the vehicle id breaks every tie, so the order is reproducible
 * and a fleet of identical vehicles still fills the map deterministically.
 */

import type { Vehicle } from '../api/types'

/**
 * Most vehicles the map ever draws as individual mini vehicles.
 *
 * It is a hard ceiling and not a default: the control of the dashboard picks
 * from {@link VISIBLE_COUNT_OPTIONS}, and a larger value - from a stale
 * bookmark, a query string or a future control - is clamped to this one.
 */
export const MAX_RENDERED_VEHICLES = 100

/** The counts a reader picks from, all of them at or below the ceiling. */
export const VISIBLE_COUNT_OPTIONS = [25, 50, 100] as const

/** The count the map opens with. */
export const DEFAULT_VISIBLE_COUNT = 50

/**
 * Most vehicles the faint cloud carries.
 *
 * The cloud is one buffer of points, so the limit is about the memory of that
 * buffer rather than about the work per frame; a fleet larger than this is
 * sampled down to it deterministically, and the count that did not fit is
 * reported back so the panel can say so.
 */
export const MAX_BACKGROUND_VEHICLES = 20_000

/** One budgeted fleet: what is drawn, and what is only acknowledged. */
export interface FleetSplit {
  /** Vehicles drawn as their own mini vehicle, most interesting first. */
  rendered: Vehicle[]
  /** The rest of the fleet, faint, in one cloud. */
  background: Vehicle[]
  /** Vehicles of the fleet beyond {@link MAX_BACKGROUND_VEHICLES} that the cloud could not carry. */
  omitted: number
}

/**
 * A small integer fingerprint of a vehicle id.
 *
 * FNV-1a, on purpose: it is a handful of arithmetic operations, it is the same
 * in every JavaScript engine, and it never touches `Math.random`, so the order
 * of the map is reproducible.
 *
 * @param value text to fingerprint
 * @returns an unsigned 32 bit number
 */
export function stableHash(value: string): number {
  let hash = 0x811c9dc5
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index)
    hash = Math.imul(hash, 0x01000193)
  }
  return hash >>> 0
}

/**
 * @param limit requested number of drawn vehicles
 * @returns the limit clamped to the options and the ceiling
 */
export function clampVisibleCount(limit: number): number {
  if (!Number.isFinite(limit)) {
    return DEFAULT_VISIBLE_COUNT
  }
  const rounded = Math.round(limit)
  return Math.min(MAX_RENDERED_VEHICLES, Math.max(1, rounded))
}

/**
 * Splits a fleet into what the map draws and what it acknowledges.
 *
 * @param vehicles the fleet as the backend last reported it
 * @param limit how many vehicles may be drawn as mini vehicles
 * @param selectedVehicleId the vehicle a reader selected, if any
 * @returns the two halves and how many vehicles the cloud could not carry
 */
export function splitFleet(
  vehicles: readonly Vehicle[],
  limit: number = DEFAULT_VISIBLE_COUNT,
  selectedVehicleId: string | null = null,
): FleetSplit {
  const budget = clampVisibleCount(limit)
  const selected = selectedVehicleId === null ? null : vehicles.find((entry) => entry.vehicleId === selectedVehicleId) ?? null
  const pool = selected ? vehicles.filter((entry) => entry !== selected) : [...vehicles]

  const byHash = (left: Vehicle, right: Vehicle): number => {
    const byFingerprint = stableHash(left.vehicleId) - stableHash(right.vehicleId)
    return byFingerprint !== 0 ? byFingerprint : left.vehicleId.localeCompare(right.vehicleId)
  }
  const moving = pool.filter((entry) => entry.status === 'MOVING').sort(byHash)
  const stopped = pool.filter((entry) => entry.status !== 'MOVING').sort(byHash)

  const room = Math.max(0, budget - (selected ? 1 : 0))
  // The share of the budget the parked vehicles get, rounded, and never so
  // small that a fleet with parked vehicles draws none of them.
  const shareOfStopped = pool.length === 0 ? 0 : stopped.length / pool.length
  const wantedStopped = stopped.length === 0 || room === 0 ? 0 : Math.max(1, Math.round(room * shareOfStopped))
  const drawnStopped = Math.min(stopped.length, wantedStopped, room)

  const rendered = [
    ...(selected ? [selected] : []),
    ...stopped.slice(0, drawnStopped),
    ...moving.slice(0, room - drawnStopped),
  ]
  const drawn = new Set(rendered.map((entry) => entry.vehicleId))
  const rest = vehicles.filter((entry) => !drawn.has(entry.vehicleId)).sort(byHash)
  const background = rest.length <= MAX_BACKGROUND_VEHICLES ? rest : sampleEvenly(rest, MAX_BACKGROUND_VEHICLES)
  return { rendered, background, omitted: rest.length - background.length }
}

/**
 * Takes at most `count` items out of a list, spread over the whole of it.
 *
 * @param items list to sample
 * @param count most items to keep
 * @returns the sampled list, in the order of the input
 */
export function sampleEvenly<T>(items: readonly T[], count: number): T[] {
  if (count <= 0 || items.length === 0) {
    return []
  }
  if (items.length <= count) {
    return [...items]
  }
  const step = items.length / count
  const sampled: T[] = []
  for (let index = 0; index < count; index += 1) {
    const source = Math.min(items.length - 1, Math.floor(index * step))
    sampled.push(items[source])
  }
  return sampled
}
