/**
 * The procedural district the fleet drives through.
 *
 * There is no tile server behind this map and no paid provider: the ground is
 * generated from a seed and the extent of the fleet, so the same fleet is
 * always drawn over the same streets, and the map works on a machine with no
 * network at all. What it draws is a depot city - a street grid, a ring road,
 * two avenues, blocks, a park, a channel and the depot itself - laid out in
 * local metres, which is the same space the vehicles are placed in.
 */

import { DEPOT_RADIUS_METERS } from './theme'

/** How busy the street grid is. */
export type NetworkDensity = 'sparse' | 'regular' | 'dense'

/** What a road is, which is what decides how wide it is drawn. */
export type RoadKind = 'street' | 'avenue' | 'ring'

/** One straight piece of road, as a segment between two points in metres. */
export interface Road {
  kind: RoadKind
  x1: number
  y1: number
  x2: number
  y2: number
  /** Width of the surface, in metres. */
  width: number
}

/** What a patch of ground is. */
export type DistrictKind = 'block' | 'park' | 'water' | 'depot'

/** One rectangle of ground. */
export interface District {
  kind: DistrictKind
  x: number
  y: number
  width: number
  height: number
}

/** Everything the renderer needs to draw the district once. */
export interface RoadNetwork {
  seed: number
  density: NetworkDensity
  /** Half the width of the square the district covers, in metres. */
  halfExtent: number
  /** Distance between two streets of the grid, in metres. */
  spacing: number
  roads: Road[]
  districts: District[]
}

/** How many blocks a density lays out across the district. */
const CELLS_BY_DENSITY: Record<NetworkDensity, number> = {
  sparse: 5,
  regular: 7,
  dense: 10,
}

/** A deterministic generator, so the map is the same on every machine. */
function mulberry32(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let value = Math.imul(state ^ (state >>> 15), 1 | state)
    value = (value + Math.imul(value ^ (value >>> 7), 61 | value)) ^ value
    return ((value ^ (value >>> 14)) >>> 0) / 4_294_967_296
  }
}

/**
 * @param value density a caller may or may not have given
 * @returns one of the densities of this module
 */
export function normalizeDensity(value: unknown): NetworkDensity {
  return value === 'sparse' || value === 'dense' ? value : 'regular'
}

/**
 * Lays out a district.
 *
 * @param options seed of the layout, half width of the district in metres, and how busy it is
 * @returns the roads and the districts, in the order they are drawn
 */
export function buildRoadNetwork(options: {
  seed: number
  halfExtent: number
  density?: unknown
}): RoadNetwork {
  const density = normalizeDensity(options.density)
  const halfExtent = Math.max(400, options.halfExtent)
  const cells = CELLS_BY_DENSITY[density]
  const spacing = (halfExtent * 2) / cells
  const random = mulberry32(options.seed)

  const roads: Road[] = []
  const districts: District[] = []

  const inset = spacing * 0.14
  const middle = (cells - 1) / 2

  for (let row = 0; row < cells; row += 1) {
    for (let column = 0; column < cells; column += 1) {
      const x = -halfExtent + column * spacing + inset
      const y = -halfExtent + row * spacing + inset
      const isCenter = row === Math.floor(middle) && column === Math.floor(middle)
      const roll = random()
      const kind: DistrictKind = isCenter ? 'depot' : roll < 0.12 ? 'park' : roll < 0.18 ? 'water' : 'block'
      districts.push({ kind, x, y, width: spacing - inset * 2, height: spacing - inset * 2 })
    }
  }

  const streetWidth = spacing * 0.055
  for (let index = 1; index < cells; index += 1) {
    const offset = -halfExtent + index * spacing
    roads.push({ kind: 'street', x1: offset, y1: -halfExtent, x2: offset, y2: halfExtent, width: streetWidth })
    roads.push({ kind: 'street', x1: -halfExtent, y1: offset, x2: halfExtent, y2: offset, width: streetWidth })
  }

  const avenueWidth = spacing * 0.24
  const avenue = halfExtent * 0.94
  roads.push({ kind: 'avenue', x1: -avenue, y1: -avenue, x2: avenue, y2: avenue, width: avenueWidth })
  roads.push({ kind: 'avenue', x1: -avenue, y1: avenue, x2: avenue, y2: -avenue, width: avenueWidth })

  const ringWidth = spacing * 0.34
  const ring = halfExtent * 0.66
  roads.push({ kind: 'ring', x1: -ring, y1: -ring, x2: ring, y2: -ring, width: ringWidth })
  roads.push({ kind: 'ring', x1: ring, y1: -ring, x2: ring, y2: ring, width: ringWidth })
  roads.push({ kind: 'ring', x1: ring, y1: ring, x2: -ring, y2: ring, width: ringWidth })
  roads.push({ kind: 'ring', x1: -ring, y1: ring, x2: -ring, y2: -ring, width: ringWidth })

  const depotRadius = Math.max(DEPOT_RADIUS_METERS, spacing * 0.45)
  districts.push({
    kind: 'depot',
    x: -depotRadius,
    y: -depotRadius * 0.7,
    width: depotRadius * 2,
    height: depotRadius * 1.4,
  })

  return { seed: options.seed, density, halfExtent, spacing, roads, districts }
}

/**
 * Whether a district already covers the ground the fleet needs.
 *
 * The layout is generated once and only rebuilt when the fleet grows well past
 * it - a fleet that arrives a kilometre further out does not redraw the map, so
 * the ground a reader is looking at stays where it is.
 *
 * @param generatedHalfExtent half width of the district that was laid out, `0` for none
 * @param requiredHalfExtent half width of the ground the fleet needs now
 * @param tolerance how much of a shortfall is accepted, as a fraction
 * @returns whether a rebuild is needed
 */
export function networkCoversExtent(
  generatedHalfExtent: number,
  requiredHalfExtent: number,
  tolerance = 0.25,
): boolean {
  return generatedHalfExtent > 0 && generatedHalfExtent * (1 + tolerance) >= requiredHalfExtent
}
