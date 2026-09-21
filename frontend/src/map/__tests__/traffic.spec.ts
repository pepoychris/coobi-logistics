import { describe, expect, it } from 'vitest'

import {
  buildRoadNetwork,
  insideBuilding,
  nearestRoad,
  onRoadSurface,
  pointOnRoad,
  roadLength,
  type RoadNetwork,
} from '../roadNetwork'
import { planTraffic, SLOT_LENGTH_METERS, type TrafficEntry, type TrafficPlacement } from '../traffic'

/**
 * The tests of the placer.
 *
 * They pin down the four things a reader of the map would notice immediately if
 * they broke: no vehicle on a block or a roof, no two vehicles sharing a piece
 * of lane, a fleet that reports itself at one point spreading over the streets
 * instead of piling over one intersection, and the same fleet always landing on
 * the same streets.
 */

function district(halfExtent = 2_000, density = 'regular'): RoadNetwork {
  return buildRoadNetwork({ seed: 20_260_101, halfExtent, density })
}

/** A fleet that reports every one of its vehicles at the same point. */
function cluster(count: number, at: { x: number; y: number } = { x: 0, y: 0 }): TrafficEntry[] {
  return Array.from({ length: count }, (_, index) => ({
    vehicleId: `TRUCK-${String(index + 1).padStart(5, '0')}`,
    x: at.x,
    y: at.y,
    heading: (index * 37) % 360,
  }))
}

function values(placements: Map<string, TrafficPlacement>): TrafficPlacement[] {
  return [...placements.values()]
}

function slotKey(placement: TrafficPlacement): string {
  return `${placement.roadIndex}:${placement.lane}:${placement.slot}`
}

describe('the fleet on the roads', () => {
  it('seats every vehicle of a crowded district on a road, and none on a building', () => {
    const network = district()
    const placements = planTraffic(network, cluster(600))

    expect(placements.size).toBe(600)
    for (const placement of values(placements)) {
      expect(onRoadSurface(network, placement)).toBe(true)
      expect(insideBuilding(network, placement)).toBe(false)
    }
  })

  it('draws every vehicle on a lane of the road it is seated on', () => {
    const network = district()
    const placements = planTraffic(network, cluster(300))

    for (const placement of values(placements)) {
      const road = network.roads[placement.roadIndex]
      expect(placement.lane).toBeGreaterThanOrEqual(0)
      expect(placement.lane).toBeLessThan(road.lanes)
      expect(placement.along).toBeGreaterThanOrEqual(0)
      expect(placement.along).toBeLessThanOrEqual(roadLength(road))
      expect(placement.along).toBeCloseTo(placement.slot * SLOT_LENGTH_METERS + SLOT_LENGTH_METERS / 2, 6)
      const onLane = pointOnRoad(road, placement.along, placement.lane)
      expect(placement.x).toBeCloseTo(onLane.x, 6)
      expect(placement.y).toBeCloseTo(onLane.y, 6)
      expect(placement.heading).toBeCloseTo(onLane.heading, 6)
    }
  })

  it('never seats two vehicles in the same slot of the same lane', () => {
    const network = district()
    const placements = planTraffic(network, cluster(600))
    const keys = new Set(values(placements).map(slotKey))

    expect(keys.size).toBe(placements.size)
    expect(values(placements).every((placement) => !placement.shared)).toBe(true)
  })

  it('spreads a fleet that reports itself at one point over the streets', () => {
    // A district of a few blocks, and more vehicles than fit on the avenue the
    // whole fleet reports itself on: the rest of them have to drive on.
    const network = district(480, 'sparse')
    const placements = planTraffic(network, cluster(1_200))
    const points = values(placements)
    const roads = new Set(points.map((placement) => placement.roadIndex))
    const xs = points.map((placement) => placement.x)
    const ys = points.map((placement) => placement.y)

    expect(roads.size).toBeGreaterThanOrEqual(3)
    expect(Math.max(...xs) - Math.min(...xs)).toBeGreaterThan(network.spacing)
    expect(Math.max(...ys) - Math.min(...ys)).toBeGreaterThan(0)
    // Whatever street each of them ended up on, all of them are on a street.
    expect(points.every((placement) => onRoadSurface(network, placement))).toBe(true)
    expect(points.every((placement) => !insideBuilding(network, placement))).toBe(true)
  })

  it('gives the same fleet the same streets, whatever order it arrives in', () => {
    const network = district()
    const entries = cluster(400)

    const first = planTraffic(network, entries)
    const again = planTraffic(network, entries)
    const reversed = planTraffic(network, [...entries].reverse())

    expect([...again]).toEqual([...first])
    expect([...reversed]).toEqual([...first])
  })

  it('keeps a vehicle on the road it reported when that road still has room', () => {
    const network = district()
    const avenue = network.roads.find((road) => road.orientation === 'vertical' && road.kind === 'avenue')!
    const entry: TrafficEntry = { vehicleId: 'TRUCK-00001', x: avenue.x1 + 40, y: 250, heading: 0 }
    const reported = nearestRoad(network, entry)

    const placement = planTraffic(network, [entry]).get(entry.vehicleId)!

    expect(placement.shared).toBe(false)
    expect(placement.roadIndex).toBe(reported?.roadIndex)
    expect(Math.abs(placement.along - (reported?.along ?? 0))).toBeLessThanOrEqual(SLOT_LENGTH_METERS)
  })

  it('snaps the heading of a vehicle to the lane it is driving in', () => {
    const network = district()
    const street = network.roads.find(
      (road) => road.orientation === 'horizontal' && road.kind === 'street',
    )!
    const eastbound: TrafficEntry = { vehicleId: 'TRUCK-00001', x: 0, y: street.y1 + 15, heading: 92 }
    const westbound: TrafficEntry = { vehicleId: 'TRUCK-00002', x: 0, y: street.y1 + 15, heading: 268 }

    const placements = planTraffic(network, [eastbound, westbound])
    const east = placements.get(eastbound.vehicleId)!
    const west = placements.get(westbound.vehicleId)!

    // 92 degrees is east on an east-west street and 268 is west: each of them is
    // drawn pointing along the lane it keeps to rather than at the angle it
    // reported, which is what a vehicle that never leaves the road looks like.
    expect(east.heading).toBe(90)
    expect(west.heading).toBe(270)
    // Traffic keeps to its right: eastbound south of the centre, westbound north.
    expect(east.y).toBeLessThan(street.y1)
    expect(west.y).toBeGreaterThan(street.y1)
    expect(east.roadIndex).toBe(west.roadIndex)
    expect(east.lane).not.toBe(west.lane)
  })

  it('answers with legal ground even when a district is full, and says when it shares', () => {
    const network = district(480, 'sparse')
    const placements = planTraffic(network, cluster(4_000))
    const points = values(placements)

    expect(points).toHaveLength(4_000)
    expect(points.some((placement) => placement.shared)).toBe(true)
    expect(points.every((placement) => onRoadSurface(network, placement))).toBe(true)
    expect(points.every((placement) => !insideBuilding(network, placement))).toBe(true)
    // The vehicles that got a slot of their own still have one each.
    const owned = points.filter((placement) => !placement.shared)
    expect(new Set(owned.map(slotKey)).size).toBe(owned.length)
  })

  it('pulls a fleet that reports itself far outside the district onto its ring road', () => {
    const network = district()
    const placements = planTraffic(network, [
      { vehicleId: 'TRUCK-00001', x: 400_000, y: 400_000, heading: 180 },
      { vehicleId: 'TRUCK-00002', x: -400_000, y: -400_000, heading: 0 },
    ])

    for (const placement of values(placements)) {
      expect(onRoadSurface(network, placement)).toBe(true)
      expect(insideBuilding(network, placement)).toBe(false)
    }
    expect(new Set(values(placements).map((placement) => placement.roadIndex)).size).toBeGreaterThan(0)
  })
})
