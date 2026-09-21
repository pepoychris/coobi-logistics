import { describe, expect, it } from 'vitest'

import {
  buildRoadNetwork,
  gridRoad,
  headingOfRoad,
  insideBuilding,
  laneOffset,
  laneTravelsForward,
  lanesInDirection,
  MIN_DISTRICT_HALF_EXTENT,
  narrowestRoadWidth,
  nearestRoad,
  networkCoversExtent,
  normalizeDensity,
  normalizeHeading,
  onRoadSurface,
  pointOnRoad,
  roadLength,
  type Building,
  type District,
  type Road,
  type RoadNetwork,
} from '../roadNetwork'

/** A rectangle of the ground, as the tests of the layout compare them. */
type Rectangle = { x: number; y: number; width: number; height: number }

function district(options: { halfExtent?: number; density?: unknown; seed?: number } = {}): RoadNetwork {
  return buildRoadNetwork({
    seed: options.seed ?? 20_260_101,
    halfExtent: options.halfExtent ?? 2_000,
    density: options.density,
  })
}

/** The rectangle the surface of one road covers. */
function surfaceOf(road: Road): Rectangle {
  const width = Math.abs(road.x2 - road.x1)
  const height = Math.abs(road.y2 - road.y1)
  return {
    x: Math.min(road.x1, road.x2) - road.width / 2,
    y: Math.min(road.y1, road.y2) - road.width / 2,
    width: width + road.width,
    height: height + road.width,
  }
}

function overlaps(left: Rectangle, right: Rectangle): boolean {
  return (
    left.x < right.x + right.width &&
    left.x + left.width > right.x &&
    left.y < right.y + right.height &&
    left.y + left.height > right.y
  )
}

function contains(outer: Rectangle, inner: Rectangle, tolerance = 0.001): boolean {
  return (
    inner.x >= outer.x - tolerance &&
    inner.y >= outer.y - tolerance &&
    inner.x + inner.width <= outer.x + outer.width + tolerance &&
    inner.y + inner.height <= outer.y + outer.height + tolerance
  )
}

describe('the district of the fleet', () => {
  it('lays out the same city for the same seed', () => {
    const first = district({ seed: 20_260_101 })
    const second = district({ seed: 20_260_101 })

    expect(second).toEqual(first)
  })

  it('lays out a different city for a different seed', () => {
    const first = district({ seed: 1 })
    const second = district({ seed: 2 })

    expect(first.districts).not.toEqual(second.districts)
    expect(first.buildings).not.toEqual(second.buildings)
  })

  it('covers the whole ground it was asked for', () => {
    const network = district({ halfExtent: 3_000, density: 'dense' })

    expect(network.halfExtent).toBe(3_000)
    for (const line of [...network.xLines, ...network.yLines]) {
      expect(Math.abs(line)).toBeLessThanOrEqual(3_000)
    }
    for (const patch of [...network.districts, ...network.buildings]) {
      expect(patch.x).toBeGreaterThanOrEqual(-3_000)
      expect(patch.x + patch.width).toBeLessThanOrEqual(3_000)
      expect(patch.y).toBeGreaterThanOrEqual(-3_000)
      expect(patch.y + patch.height).toBeLessThanOrEqual(3_000)
    }
  })

  it('lays a grid of streets, a ring road around it and two avenues through it', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const vertical = network.roads.filter((road) => road.orientation === 'vertical')
    const horizontal = network.roads.filter((road) => road.orientation === 'horizontal')
    const ring = network.roads.filter((road) => road.kind === 'ring')
    const avenues = network.roads.filter((road) => road.kind === 'avenue')
    const streets = network.roads.filter((road) => road.kind === 'street')

    // Seven blocks across means eight lines each way.
    expect(vertical).toHaveLength(8)
    expect(horizontal).toHaveLength(8)
    expect(network.spacing).toBeCloseTo((2_000 * 2) / 7, 6)
    // The two outer lines of each axis carry the ring and the two middle ones
    // the avenues, so four of each across the district.
    expect(ring).toHaveLength(4)
    expect(avenues).toHaveLength(4)
    expect(streets).toHaveLength(8 + 8 - 4 - 4)
    expect(ring.every((road) => road.kind === 'ring' && road.lanes === 4)).toBe(true)
    expect(avenues.every((road) => road.lanes === 4)).toBe(true)
    expect(streets.every((road) => road.lanes === 2)).toBe(true)
    expect(ring[0].width).toBeGreaterThan(streets[0].width)
    expect(avenues[0].width).toBeGreaterThan(ring[0].width)
  })

  it('keeps every block, and every building on it, off the roads', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const surfaces = network.roads.map(surfaceOf)

    for (const patch of network.districts) {
      for (const surface of surfaces) {
        expect(overlaps(patch, surface)).toBe(false)
      }
    }
    for (const building of network.buildings) {
      for (const surface of surfaces) {
        expect(overlaps(building, surface)).toBe(false)
      }
    }
    // And the pavement between a road and the plot behind it is wide enough to
    // walk down, rather than a hairline that would read as a rounding error.
    const plot = network.districts[0]
    const nearest = network.xLines[0]
    expect(plot.x - nearest).toBeGreaterThan(network.spacing * 0.05)
  })

  it('stands every building on the block it belongs to, and none on another', () => {
    const network = district({ halfExtent: 2_000, density: 'dense' })
    const blocks = network.districts.filter((patch) => patch.kind === 'block')

    expect(network.buildings.length).toBeGreaterThan(0)
    for (const building of network.buildings) {
      const host = blocks.filter((block) => contains(block, building))
      expect(host).toHaveLength(1)
    }
    // Two buildings of the same block never overlap each other.
    const lots: readonly Building[] = network.buildings
    for (let index = 0; index < lots.length; index += 1) {
      for (let other = index + 1; other < lots.length; other += 1) {
        if (overlaps(lots[index], lots[other])) {
          throw new Error(`two buildings overlap at ${index} and ${other}`)
        }
      }
    }
    expect(network.buildings.every((building) => building.levels >= 1)).toBe(true)
  })

  it('puts the depot on the middle block of the district', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const depots = network.districts.filter((patch) => patch.kind === 'depot')

    expect(depots).toHaveLength(1)
    const depot = depots[0] as District
    const middle = network.xLines[Math.floor(network.cells / 2)]
    expect(depot.x).toBeGreaterThan(middle)
    expect(depot.y).toBeGreaterThan(network.yLines[Math.floor(network.cells / 2)])
    // Nothing drives through the yard of the depot.
    expect(network.buildings.some((building) => overlaps(depot, building))).toBe(false)
  })

  it('never lays out a district smaller than the minimum', () => {
    const tiny = district({ halfExtent: 10 })

    expect(tiny.halfExtent).toBe(MIN_DISTRICT_HALF_EXTENT)
    expect(tiny.roads.length).toBeGreaterThan(0)
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

describe('the lanes of a road', () => {
  it('divides a road into as many lanes each way as its kind carries', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const street = network.roads.find((road) => road.kind === 'street' && road.orientation === 'vertical')!
    const avenue = network.roads.find((road) => road.kind === 'avenue' && road.orientation === 'vertical')!

    expect(street.lanes).toBe(2)
    expect(lanesInDirection(street)).toBe(1)
    expect(lanesInDirection(avenue)).toBe(2)
    expect(laneTravelsForward(street, 1)).toBe(true)
    expect(laneTravelsForward(street, 0)).toBe(false)
    expect(laneTravelsForward(avenue, 3)).toBe(true)
    expect(laneTravelsForward(avenue, 0)).toBe(false)
  })

  it('mirrors the lanes of a road across its centre, and keeps a van inside it', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const avenue = network.roads.find((road) => road.kind === 'avenue' && road.orientation === 'horizontal')!

    for (let lane = 0; lane < avenue.lanes; lane += 1) {
      const opposite = avenue.lanes - 1 - lane
      expect(laneOffset(avenue, lane)).toBeCloseTo(-laneOffset(avenue, opposite), 9)
      expect(Math.abs(laneOffset(avenue, lane)) + 1.1).toBeLessThan(avenue.width / 2)
    }
    // The innermost lane of each direction is the one nearest the centre.
    expect(Math.abs(laneOffset(avenue, 1))).toBeLessThan(Math.abs(laneOffset(avenue, 0)))
    expect(Math.abs(laneOffset(avenue, 2))).toBeLessThan(Math.abs(laneOffset(avenue, 3)))
  })

  it('points a vehicle the way the lane it keeps to travels', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const vertical = network.roads.find((road) => road.orientation === 'vertical')!
    const horizontal = network.roads.find((road) => road.orientation === 'horizontal')!
    const verticalStreet = network.roads.find(
      (road) => road.orientation === 'vertical' && road.kind === 'street',
    )!
    const horizontalAvenue = network.roads.find(
      (road) => road.orientation === 'horizontal' && road.kind === 'avenue',
    )!

    expect(headingOfRoad(vertical, true)).toBe(0)
    expect(headingOfRoad(vertical, false)).toBe(180)
    expect(headingOfRoad(horizontal, true)).toBe(90)
    expect(headingOfRoad(horizontal, false)).toBe(270)
    // A street carries one lane each way, a ring or an avenue two.
    expect(pointOnRoad(verticalStreet, 100, 1).heading).toBe(0)
    expect(pointOnRoad(verticalStreet, 100, 0).heading).toBe(180)
    expect(pointOnRoad(horizontalAvenue, 100, 3).heading).toBe(90)
    expect(pointOnRoad(horizontalAvenue, 100, 0).heading).toBe(270)
    expect(normalizeHeading(-90)).toBe(270)
    expect(normalizeHeading(450)).toBe(90)
  })

  it('keeps traffic to its right, on both axes', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const vertical = network.roads.find((road) => road.orientation === 'vertical')!
    const horizontal = network.roads.find((road) => road.orientation === 'horizontal')!

    // Northbound traffic keeps to the east of a northbound road, and eastbound
    // traffic to the south of an eastbound one.
    const northbound = pointOnRoad(vertical, 200, vertical.lanes - 1)
    const southbound = pointOnRoad(vertical, 200, 0)
    expect(northbound.x).toBeGreaterThan(vertical.x1)
    expect(southbound.x).toBeLessThan(vertical.x1)

    const eastbound = pointOnRoad(horizontal, 200, horizontal.lanes - 1)
    const westbound = pointOnRoad(horizontal, 200, 0)
    expect(eastbound.y).toBeLessThan(horizontal.y1)
    expect(westbound.y).toBeGreaterThan(horizontal.y1)
  })

  it('clamps a position to the road it is asked for', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const road = network.roads[0]

    expect(pointOnRoad(road, -500, 1).y).toBe(pointOnRoad(road, 0, 1).y)
    expect(pointOnRoad(road, roadLength(road) + 500, 1).y).toBe(
      pointOnRoad(road, roadLength(road), 1).y,
    )
  })
})

describe('reading the district', () => {
  it('finds the road nearest a position, and how far along it the position is', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const vertical = network.roads.find((road) => road.orientation === 'vertical' && road.lineIndex === 1)!

    const hit = nearestRoad(network, { x: vertical.x1 + 20, y: 100 })

    expect(hit?.roadId).toBe(vertical.id)
    expect(hit?.distance).toBeCloseTo(20, 6)
    expect(hit?.along).toBeCloseTo(100 + network.halfExtent, 6)
  })

  it('answers whether a position is on a road, and whether it stands on a building', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })
    const road = network.roads[0]
    const block = network.districts.find((patch) => patch.kind === 'block')!
    const inside = { x: block.x + block.width / 2, y: block.y + block.height / 2 }
    const building = network.buildings.find((footprint) => contains(block, footprint))!

    expect(onRoadSurface(network, pointOnRoad(road, 400, 1))).toBe(true)
    expect(onRoadSurface(network, inside)).toBe(false)
    expect(insideBuilding(network, inside)).toBe(false)
    expect(onRoadSurface(network, inside, network.spacing)).toBe(true)
    expect(insideBuilding(network, { x: building.x + 1, y: building.y + 1 })).toBe(true)
    expect(onRoadSurface(network, { x: building.x + 1, y: building.y + 1 })).toBe(false)
  })

  it('measures its own narrowest road', () => {
    const network = district({ halfExtent: 2_000, density: 'sparse' })

    const narrowest = Math.min(...network.roads.map((road) => road.width))

    expect(narrowestRoadWidth(network)).toBeCloseTo(narrowest, 9)
    expect(narrowestRoadWidth(network)).toBeGreaterThan(0)
  })

  it('names the road that runs along a line of the grid', () => {
    const network = district({ halfExtent: 2_000, density: 'regular' })

    expect(gridRoad(network, 'vertical', 3)?.id).toBe('V3')
    expect(gridRoad(network, 'horizontal', 2)?.id).toBe('H2')
    expect(gridRoad(network, 'vertical', network.cells)?.kind).toBe('ring')
    expect(gridRoad(network, 'horizontal', -1)).toBeNull()
    expect(gridRoad(network, 'vertical', network.cells + 1)).toBeNull()
  })
})
