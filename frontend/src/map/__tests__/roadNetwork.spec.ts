import { describe, expect, it } from 'vitest'

import { buildRoadNetwork, networkCoversExtent, normalizeDensity } from '../roadNetwork'

describe('the procedural district', () => {
  it('lays out the same city for the same seed', () => {
    const first = buildRoadNetwork({ seed: 20260101, halfExtent: 2_000, density: 'regular' })
    const second = buildRoadNetwork({ seed: 20260101, halfExtent: 2_000, density: 'regular' })

    expect(second).toEqual(first)
  })

  it('lays out a different city for a different seed', () => {
    const first = buildRoadNetwork({ seed: 1, halfExtent: 2_000 })
    const second = buildRoadNetwork({ seed: 2, halfExtent: 2_000 })

    expect(second.districts).not.toEqual(first.districts)
  })

  it('covers the whole ground it was asked for', () => {
    const network = buildRoadNetwork({ seed: 7, halfExtent: 3_000, density: 'dense' })

    expect(network.halfExtent).toBe(3_000)
    for (const road of network.roads) {
      for (const coordinate of [road.x1, road.y1, road.x2, road.y2]) {
        expect(Math.abs(coordinate)).toBeLessThanOrEqual(3_000)
      }
    }
    for (const district of network.districts) {
      expect(district.x).toBeGreaterThanOrEqual(-3_000)
      expect(district.x + district.width).toBeLessThanOrEqual(3_000)
      expect(district.y).toBeGreaterThanOrEqual(-3_000)
      expect(district.y + district.height).toBeLessThanOrEqual(3_000)
    }
  })

  it('draws a grid, two avenues and one ring road around the depot', () => {
    const network = buildRoadNetwork({ seed: 11, halfExtent: 2_100, density: 'regular' })

    const streets = network.roads.filter((road) => road.kind === 'street')
    const avenues = network.roads.filter((road) => road.kind === 'avenue')
    const ring = network.roads.filter((road) => road.kind === 'ring')

    expect(streets).toHaveLength(12)
    expect(avenues).toHaveLength(2)
    expect(ring).toHaveLength(4)
    expect(network.spacing).toBeCloseTo((2_100 * 2) / 7, 6)
    expect(network.roads.filter((road) => road.kind === 'ring').every((road) => road.width > 0)).toBe(true)
  })

  it('gives every block of the grid a patch of ground, and the depot one of its own', () => {
    const network = buildRoadNetwork({ seed: 3, halfExtent: 1_400, density: 'sparse' })

    expect(network.districts).toHaveLength(5 * 5 + 1)
    expect(network.districts.filter((district) => district.kind === 'depot')).toHaveLength(2)
    expect(network.districts.some((district) => district.kind === 'block')).toBe(true)
  })

  it('lays the ground out again only when the fleet no longer fits in it', () => {
    expect(networkCoversExtent(0, 2_000)).toBe(false)
    expect(networkCoversExtent(2_000, 2_000)).toBe(true)
    expect(networkCoversExtent(2_000, 2_400)).toBe(true)
    expect(networkCoversExtent(2_000, 3_000)).toBe(false)
    expect(networkCoversExtent(2_000, 3_000, 0.5)).toBe(true)
  })

  it('falls back to the regular grid for a density it does not know', () => {
    expect(normalizeDensity('dense')).toBe('dense')
    expect(normalizeDensity('sparse')).toBe('sparse')
    expect(normalizeDensity('enormous')).toBe('regular')
    expect(normalizeDensity(undefined)).toBe('regular')
  })
})
