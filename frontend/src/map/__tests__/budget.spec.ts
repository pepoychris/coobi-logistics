import { describe, expect, it } from 'vitest'

import { vehicle } from '../../__tests__/support/fixtures'
import {
  clampVisibleCount,
  DEFAULT_VISIBLE_COUNT,
  MAX_BACKGROUND_VEHICLES,
  MAX_RENDERED_VEHICLES,
  sampleEvenly,
  splitFleet,
  stableHash,
  VISIBLE_COUNT_OPTIONS,
} from '../budget'

/** A fleet of `count` vehicles, half of them on the move. */
function fleet(count: number, movingEvery = 2) {
  return Array.from({ length: count }, (_, index) =>
    vehicle({
      vehicleId: `TRUCK-${String(index).padStart(5, '0')}`,
      status: index % movingEvery === 0 ? 'MOVING' : 'STOPPED',
    }),
  )
}

describe('the rendering budget of the map', () => {
  it('never draws more vehicles than the ceiling allows', () => {
    const split = splitFleet(fleet(240), 400)

    expect(split.rendered).toHaveLength(MAX_RENDERED_VEHICLES)
    expect(split.background).toHaveLength(140)
    expect(split.omitted).toBe(0)
  })

  it('draws at most what a reader asked for and acknowledges the rest', () => {
    const split = splitFleet(fleet(150), 50)

    expect(split.rendered).toHaveLength(50)
    expect(split.background).toHaveLength(100)
  })

  it('splits the same fleet the same way whatever order it arrives in', () => {
    const ordered = fleet(120)
    const shuffled = [...ordered].reverse()

    const first = splitFleet(ordered, 40)
    const second = splitFleet(shuffled, 40)

    expect(first.rendered.map((entry) => entry.vehicleId)).toEqual(second.rendered.map((entry) => entry.vehicleId))
  })

  it('keeps the mix of the fleet, so a movement state is never hidden by the budget', () => {
    const stopped = Array.from({ length: 40 }, (_, index) =>
      vehicle({ vehicleId: `PARKED-${index}`, status: 'STOPPED' }),
    )
    const moving = Array.from({ length: 40 }, (_, index) =>
      vehicle({ vehicleId: `DRIVING-${index}`, status: 'MOVING' }),
    )

    const split = splitFleet([...stopped, ...moving], 20)

    expect(split.rendered.filter((entry) => entry.status === 'MOVING')).toHaveLength(10)
    expect(split.rendered.filter((entry) => entry.status === 'STOPPED')).toHaveLength(10)
  })

  it('draws at least one parked vehicle while the fleet has any, however busy it is', () => {
    const split = splitFleet(fleet(1_000), 25, null)
    // One vehicle in a hundred is parked, and it is still on the map.
    const parked = [...fleet(1_000)].map((entry, index) =>
      index === 0 ? { ...entry, status: 'STOPPED' as const } : entry,
    )
    const mixed = splitFleet(parked, 25)

    expect(split.rendered).toHaveLength(25)
    expect(mixed.rendered.some((entry) => entry.status === 'STOPPED')).toBe(true)
  })

  it('always draws the vehicle a reader selected, wherever it is in the fleet', () => {
    const chosen = vehicle({ vehicleId: 'TRUCK-99999', status: 'STOPPED' })
    const split = splitFleet([...fleet(300), chosen], 25, 'TRUCK-99999')

    expect(split.rendered[0].vehicleId).toBe('TRUCK-99999')
    expect(split.rendered).toHaveLength(25)
  })

  it('samples the cloud down when a fleet is larger than the buffer', () => {
    const split = splitFleet(fleet(30_000), 100)

    expect(split.background).toHaveLength(MAX_BACKGROUND_VEHICLES)
    expect(split.omitted).toBe(30_000 - 100 - MAX_BACKGROUND_VEHICLES)
  })

  it('clamps a count that came from anywhere to what the map can draw', () => {
    expect(clampVisibleCount(10_000)).toBe(MAX_RENDERED_VEHICLES)
    expect(clampVisibleCount(0)).toBe(1)
    expect(clampVisibleCount(Number.NaN)).toBe(DEFAULT_VISIBLE_COUNT)
    expect(VISIBLE_COUNT_OPTIONS).toEqual([25, 50, 100])
  })

  it('fingerprints an id the same way on every machine', () => {
    expect(stableHash('TRUCK-00001')).toBe(stableHash('TRUCK-00001'))
    expect(stableHash('TRUCK-00001')).not.toBe(stableHash('TRUCK-00002'))
    expect(stableHash('')).toBe(0x811c9dc5)
  })

  it('keeps the order of the input when it samples a list down', () => {
    const items = Array.from({ length: 10 }, (_, index) => index)

    expect(sampleEvenly(items, 5)).toEqual([0, 2, 4, 6, 8])
    expect(sampleEvenly(items, 0)).toEqual([])
    expect(sampleEvenly(items, 20)).toEqual(items)
  })
})
