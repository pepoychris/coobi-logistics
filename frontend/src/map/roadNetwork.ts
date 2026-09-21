/**
 * The district the fleet drives through: the streets, the blocks behind them
 * and the buildings standing on those blocks.
 *
 * There is no tile server behind this map and no paid provider: the ground is
 * laid out from a seed and the extent of the fleet, so the same fleet is always
 * drawn over the same city, and the map works on a machine with no network at
 * all. What it lays out is a delivery district - a rectilinear grid of streets,
 * a ring road around the edge of it, two avenues through the middle, the blocks
 * between the lines, the buildings standing on those blocks and the depot the
 * fleet starts from - all in local metres, which is the same space the vehicles
 * of the map are placed in.
 *
 * The layout is what makes the rest of the map honest. Every road is a straight
 * line at a known coordinate, and every block is inset from the lines that
 * bound it by half the width of the road plus its pavement, so no block - and
 * therefore no building - can ever overlap a road. That is why the placer of
 * the fleet can ask {@link nearestRoad} for the nearest legal piece of ground
 * and {@link onRoadSurface} whether a position is on a road at all, without
 * touching the drawing code, and why a vehicle of this map cannot be seen
 * standing on a roof.
 */

import type { LocalPoint } from './projection'

/** How busy the street grid is. */
export type NetworkDensity = 'sparse' | 'regular' | 'dense'

/** What a road is, which is what decides how wide it is drawn and how many lanes it carries. */
export type RoadKind = 'street' | 'avenue' | 'ring'

/** Which way a road runs, which is what makes it an edge of the road graph. */
export type RoadOrientation = 'vertical' | 'horizontal'

/** What a patch of ground is. */
export type DistrictKind = 'block' | 'park' | 'water' | 'depot'

/** One road: a straight run of surface between two ends, divided into lanes. */
export interface Road {
  /** Stable name of the road - `V3` for the fourth vertical line - for tests and debuggers. */
  id: string
  kind: RoadKind
  orientation: RoadOrientation
  /** Index of the line the road runs along: into `xLines` when it is vertical. */
  lineIndex: number
  /** First end, in metres, as the road is travelled forwards. */
  x1: number
  y1: number
  /** Second end, in metres, as the road is travelled forwards. */
  x2: number
  y2: number
  /** Width of the surface, kerb to kerb, in metres. */
  width: number
  /** Lanes the surface is divided into, half of them in each direction. */
  lanes: number
}

/** One rectangle of ground: a block of buildings, a park, a channel or the depot. */
export interface District {
  kind: DistrictKind
  x: number
  y: number
  width: number
  height: number
}

/** One building standing on a block, seen from above. */
export interface Building {
  /** Footprint of the building, in metres. */
  x: number
  y: number
  width: number
  height: number
  /** Floors of the block, which is the height the map draws it with. */
  levels: number
}

/** Everything the renderer needs to draw the district once, and the placer needs to read. */
export interface RoadNetwork {
  seed: number
  density: NetworkDensity
  /** Half the width of the square the district covers, in metres. */
  halfExtent: number
  /** Distance between two lines of the grid, in metres. */
  spacing: number
  /** Blocks the grid is laid over, side to side. */
  cells: number
  /** Coordinates of the vertical lines of the grid, ascending. */
  xLines: readonly number[]
  /** Coordinates of the horizontal lines of the grid, ascending. */
  yLines: readonly number[]
  /** Every road of the district: the vertical lines first, then the horizontal ones. */
  roads: readonly Road[]
  districts: readonly District[]
  buildings: readonly Building[]
}

/** A position on a lane of a road, with the way a vehicle standing there is pointing. */
export interface RoadPosition {
  x: number
  y: number
  /** Degrees clockwise from north. */
  heading: number
}

/** How far along a road a position is, and how far it is from the road. */
export interface RoadDistance {
  /** Metres from the first end of the road. */
  along: number
  /** Metres between the position and the nearest point of the road. */
  distance: number
}

/** The road of a district nearest to a position. */
export interface RoadHit {
  roadIndex: number
  roadId: string
  along: number
  distance: number
}

/** The seed every dashboard lays its city out with, so every reader sees the same one. */
export const DISTRICT_SEED = 20_260_101

/** Narrowest district the map lays out, as the half width of the square, in metres. */
export const MIN_DISTRICT_HALF_EXTENT = 480

/** How many blocks a density lays out across the district. */
const CELLS_BY_DENSITY: Record<NetworkDensity, number> = {
  sparse: 5,
  regular: 7,
  dense: 10,
}

/** Width of each kind of road, as a fraction of the distance between two lines of the grid. */
const WIDTH_BY_KIND: Record<RoadKind, number> = {
  street: 0.12,
  avenue: 0.2,
  ring: 0.16,
}

/** Lanes each kind of road is divided into, half of them in each direction. */
const LANES_BY_KIND: Record<RoadKind, number> = {
  street: 2,
  avenue: 4,
  ring: 4,
}

/** Pavement between the edge of a road and the plot behind it, as a fraction of the spacing. */
const KERB_FRACTION = 0.035

/** Chance a cell of the grid is a park rather than a block of buildings. */
const PARK_CHANCE = 0.11

/** Chance a cell of the grid is a channel of water rather than a block of buildings. */
const WATER_CHANCE = 0.06

/** Buildings a block is divided into, side to side. */
const LOTS_PER_SIDE = 2

/** Chance a lot of a block carries a building rather than a yard. */
const BUILT_CHANCE = 0.82

/** Smallest side of a plot that is worth dividing into lots, in metres. */
const MIN_PLOT_SIDE = 30

/** Gap between two lots of the same block, as a fraction of the side of its plot. */
const LOT_GUTTER = 0.09

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

/**
 * Lays a district out.
 *
 * @param options seed of the layout, half width of the district in metres, and how busy it is
 * @returns the lines, the roads, the blocks and the buildings of the district
 */
export function buildRoadNetwork(options: {
  seed: number
  halfExtent: number
  density?: unknown
}): RoadNetwork {
  const density = normalizeDensity(options.density)
  const halfExtent = Math.max(MIN_DISTRICT_HALF_EXTENT, options.halfExtent)
  const cells = CELLS_BY_DENSITY[density]
  const spacing = (halfExtent * 2) / cells
  const random = mulberry32(options.seed)

  const xLines = lineCoordinates(-halfExtent, spacing, cells)
  const yLines = lineCoordinates(-halfExtent, spacing, cells)
  const vertical = xLines.map((x, index) =>
    buildRoad({
      id: `V${index}`,
      orientation: 'vertical',
      lineIndex: index,
      kind: kindOfLine(index, cells),
      x1: x,
      y1: -halfExtent,
      x2: x,
      y2: halfExtent,
      spacing,
    }),
  )
  const horizontal = yLines.map((y, index) =>
    buildRoad({
      id: `H${index}`,
      orientation: 'horizontal',
      lineIndex: index,
      kind: kindOfLine(index, cells),
      x1: -halfExtent,
      y1: y,
      x2: halfExtent,
      y2: y,
      spacing,
    }),
  )

  const kerb = spacing * KERB_FRACTION
  const districts: District[] = []
  const buildings: Building[] = []
  const depotCell = Math.floor(cells / 2)

  for (let row = 0; row < cells; row += 1) {
    for (let column = 0; column < cells; column += 1) {
      const plot = blockPlot({ xLines, yLines, vertical, horizontal, column, row, kerb })
      const kind = patchKind({ column, row, depotCell, roll: random() })
      districts.push({ kind, ...plot })
      if (kind === 'block') {
        const lots = mulberry32(Math.imul(row * cells + column + 1, 0x9e3779b1) ^ options.seed)
        buildings.push(...blockBuildings(plot, lots))
      }
    }
  }

  return {
    seed: options.seed,
    density,
    halfExtent,
    spacing,
    cells,
    xLines,
    yLines,
    roads: [...vertical, ...horizontal],
    districts,
    buildings,
  }
}

/**
 * @param from first coordinate of the grid, in metres
 * @param spacing distance between two lines, in metres
 * @param cells blocks the grid is laid over
 * @returns the `cells + 1` coordinates of the lines of one axis
 */
function lineCoordinates(from: number, spacing: number, cells: number): number[] {
  return Array.from({ length: cells + 1 }, (_, index) => from + index * spacing)
}

/**
 * What one line of the grid is: the two outer lines carry the ring road, the
 * two that straddle the middle of the district are its avenues, and the rest
 * are ordinary streets.
 *
 * @param index index of the line
 * @param cells blocks the grid is laid over
 * @returns the kind of the road that runs along it
 */
function kindOfLine(index: number, cells: number): RoadKind {
  if (index === 0 || index === cells) {
    return 'ring'
  }
  return avenueLines(cells).includes(index) ? 'avenue' : 'street'
}

/**
 * @param cells blocks the grid is laid over
 * @returns the indices of the two lines that straddle the middle of the district
 */
function avenueLines(cells: number): number[] {
  const left = Math.floor((cells - 1) / 2)
  return cells % 2 === 1 ? [left, cells - left] : [cells / 2 - 1, cells / 2]
}

/**
 * @param options the line the road runs along, its ends and the spacing of the grid
 * @returns one road of the district
 */
function buildRoad(options: {
  id: string
  kind: RoadKind
  orientation: RoadOrientation
  lineIndex: number
  x1: number
  y1: number
  x2: number
  y2: number
  spacing: number
}): Road {
  return {
    id: options.id,
    kind: options.kind,
    orientation: options.orientation,
    lineIndex: options.lineIndex,
    x1: options.x1,
    y1: options.y1,
    x2: options.x2,
    y2: options.y2,
    width: options.spacing * WIDTH_BY_KIND[options.kind],
    lanes: LANES_BY_KIND[options.kind],
  }
}

/**
 * The ground of one cell of the grid.
 *
 * The plot is what is left between the roads that bound the cell once each of
 * them keeps half of its surface and its pavement, which is what makes the
 * blocks - and every building on them - live strictly inside the grid rather
 * than under a street.
 *
 * @param options the lines and roads of the grid, the cell and the width of its pavement
 * @returns the rectangle of the plot, in metres
 */
function blockPlot(options: {
  xLines: readonly number[]
  yLines: readonly number[]
  vertical: readonly Road[]
  horizontal: readonly Road[]
  column: number
  row: number
  kerb: number
}): Omit<District, 'kind'> {
  const left = options.xLines[options.column] + options.vertical[options.column].width / 2 + options.kerb
  const right =
    options.xLines[options.column + 1] - options.vertical[options.column + 1].width / 2 - options.kerb
  const bottom = options.yLines[options.row] + options.horizontal[options.row].width / 2 + options.kerb
  const top = options.yLines[options.row + 1] - options.horizontal[options.row + 1].width / 2 - options.kerb
  return {
    x: left,
    y: bottom,
    width: Math.max(right - left, 1),
    height: Math.max(top - bottom, 1),
  }
}

/**
 * @param options the cell, the cell the depot sits on and one roll of the seed
 * @returns what stands on that cell of the district
 */
function patchKind(options: {
  column: number
  row: number
  depotCell: number
  roll: number
}): DistrictKind {
  if (options.row === options.depotCell && options.column === options.depotCell) {
    return 'depot'
  }
  if (options.roll < WATER_CHANCE) {
    return 'water'
  }
  if (options.roll < WATER_CHANCE + PARK_CHANCE) {
    return 'park'
  }
  return 'block'
}

/**
 * The buildings of one block.
 *
 * A block is divided into a small grid of lots, each of which either carries a
 * building - a rectangle inset from its lot by a roll of the seed, so the
 * buildings of a block are not all the same shape - or stays a yard. Every
 * footprint is strictly inside the plot it belongs to, which is what keeps the
 * built city and the streets apart.
 *
 * @param plot ground of the block
 * @param random deterministic generator of the block
 * @returns the buildings standing on it
 */
function blockBuildings(plot: Omit<District, 'kind'>, random: () => number): Building[] {
  if (plot.width < MIN_PLOT_SIDE || plot.height < MIN_PLOT_SIDE) {
    return []
  }
  const gutter = Math.min(plot.width, plot.height) * LOT_GUTTER
  const lotWidth = (plot.width - gutter * (LOTS_PER_SIDE - 1)) / LOTS_PER_SIDE
  const lotHeight = (plot.height - gutter * (LOTS_PER_SIDE - 1)) / LOTS_PER_SIDE
  const buildings: Building[] = []
  for (let row = 0; row < LOTS_PER_SIDE; row += 1) {
    for (let column = 0; column < LOTS_PER_SIDE; column += 1) {
      if (random() > BUILT_CHANCE) {
        continue
      }
      const insetX = lotWidth * (0.05 + random() * 0.16)
      const insetY = lotHeight * (0.05 + random() * 0.16)
      buildings.push({
        x: plot.x + column * (lotWidth + gutter) + insetX,
        y: plot.y + row * (lotHeight + gutter) + insetY,
        width: Math.max(lotWidth - insetX * 2, 1),
        height: Math.max(lotHeight - insetY * 2, 1),
        levels: 1 + Math.floor(random() * 5),
      })
    }
  }
  return buildings
}

/**
 * @param road one road of the district
 * @returns its length in metres
 */
export function roadLength(road: Road): number {
  return Math.hypot(road.x2 - road.x1, road.y2 - road.y1)
}

/**
 * @param road one road of the district
 * @returns how many of its lanes carry each of the two directions
 */
export function lanesInDirection(road: Road): number {
  return Math.max(1, Math.floor(road.lanes / 2))
}

/**
 * @param road one road of the district
 * @returns the width of one of its lanes, in metres
 */
export function laneWidth(road: Road): number {
  return road.width / Math.max(1, road.lanes)
}

/**
 * @param road one road of the district
 * @param lane index of a lane
 * @returns whether that lane carries the traffic that travels the road forwards
 */
export function laneTravelsForward(road: Road, lane: number): boolean {
  return lane >= lanesInDirection(road)
}

/**
 * Where a lane sits across its road.
 *
 * Traffic keeps to its right, so the lanes of a road are laid out from the
 * middle outwards: the innermost lane of each direction is next to the centre
 * of the road and the outermost one is against the kerb. The offset is signed,
 * positive towards the right of the way the road is travelled forwards, so a
 * vehicle is mirrored onto the other side of the road by changing one sign.
 *
 * @param road one road of the district
 * @param lane index of a lane
 * @returns the distance of the centre of the lane from the centre of the road, in metres
 */
export function laneOffset(road: Road, lane: number): number {
  const half = lanesInDirection(road)
  const forward = laneTravelsForward(road, lane)
  const position = forward ? lane - half : half - 1 - lane
  const offset = (position + 0.5) * laneWidth(road)
  return forward ? offset : -offset
}

/**
 * @param degrees an angle, possibly outside a single turn
 * @returns the same angle in `0..360`, clockwise from north
 */
export function normalizeHeading(degrees: number): number {
  if (!Number.isFinite(degrees)) {
    return 0
  }
  const wrapped = degrees % 360
  return wrapped < 0 ? wrapped + 360 : wrapped
}

/**
 * @param road one road of the district
 * @param forward whether it is travelled from its first end to its second
 * @returns the compass heading of that direction, in degrees clockwise from north
 */
export function headingOfRoad(road: Road, forward: boolean): number {
  const heading = (Math.atan2(road.x2 - road.x1, road.y2 - road.y1) * 180) / Math.PI
  return normalizeHeading(forward ? heading : heading + 180)
}

/**
 * @param road one road of the district
 * @returns the unit vector that points to the right of the way the road is travelled forwards
 */
function rightNormal(road: Road): LocalPoint {
  const length = roadLength(road)
  if (length === 0) {
    return { x: 0, y: 0 }
  }
  return { x: (road.y2 - road.y1) / length, y: -(road.x2 - road.x1) / length }
}

/**
 * One position on one lane of one road.
 *
 * @param road one road of the district
 * @param along distance from the first end of the road, in metres; clamped to the road
 * @param lane index of a lane
 * @returns where a vehicle standing there is, and where it points
 */
export function pointOnRoad(road: Road, along: number, lane: number): RoadPosition {
  const length = roadLength(road)
  const travelled = Math.min(Math.max(along, 0), length)
  const t = length === 0 ? 0 : travelled / length
  const normal = rightNormal(road)
  const offset = laneOffset(road, lane)
  return {
    x: road.x1 + (road.x2 - road.x1) * t + normal.x * offset,
    y: road.y1 + (road.y2 - road.y1) * t + normal.y * offset,
    heading: headingOfRoad(road, laneTravelsForward(road, lane)),
  }
}

/**
 * @param road one road of the district
 * @param point a position in local metres
 * @returns how far along the road it is, and how far it is from the road
 */
export function nearestRoadPoint(road: Road, point: LocalPoint): RoadDistance {
  const dx = road.x2 - road.x1
  const dy = road.y2 - road.y1
  const squared = dx * dx + dy * dy
  const raw = squared === 0 ? 0 : ((point.x - road.x1) * dx + (point.y - road.y1) * dy) / squared
  const t = Math.min(Math.max(raw, 0), 1)
  const nearestX = road.x1 + dx * t
  const nearestY = road.y1 + dy * t
  return { along: t * Math.sqrt(squared), distance: Math.hypot(point.x - nearestX, point.y - nearestY) }
}

/**
 * @param network the district
 * @param point a position in local metres
 * @returns the road of the district nearest to it, or `null` for a district with no road
 */
export function nearestRoad(network: RoadNetwork, point: LocalPoint): RoadHit | null {
  let best: RoadHit | null = null
  for (let index = 0; index < network.roads.length; index += 1) {
    const road = network.roads[index]
    const { along, distance } = nearestRoadPoint(road, point)
    if (!best || distance < best.distance) {
      best = { roadIndex: index, roadId: road.id, along, distance }
    }
  }
  return best
}

/**
 * Whether a position is on the surface of a road.
 *
 * This is the rule the fleet of the map is placed by: a vehicle is only ever
 * drawn where this answers `true`, which is why no vehicle of this map can be
 * seen standing on a block or on a roof.
 *
 * @param network the district
 * @param point a position in local metres
 * @param margin how far outside the surface still counts, in metres
 * @returns whether the position is on a road
 */
export function onRoadSurface(network: RoadNetwork, point: LocalPoint, margin = 0): boolean {
  return network.roads.some((road) => {
    const { along, distance } = nearestRoadPoint(road, point)
    return (
      distance <= road.width / 2 + margin && along >= -margin && along <= roadLength(road) + margin
    )
  })
}

/**
 * @param network the district
 * @param point a position in local metres
 * @param margin how far inside a building still counts, in metres
 * @returns whether the position stands on the footprint of a building
 */
export function insideBuilding(network: RoadNetwork, point: LocalPoint, margin = 0): boolean {
  return network.buildings.some(
    (building) =>
      point.x >= building.x - margin &&
      point.x <= building.x + building.width + margin &&
      point.y >= building.y - margin &&
      point.y <= building.y + building.height + margin,
  )
}

/**
 * @param network the district
 * @returns the width of its narrowest road, in metres
 */
export function narrowestRoadWidth(network: RoadNetwork): number {
  let narrowest = Number.POSITIVE_INFINITY
  for (const road of network.roads) {
    narrowest = Math.min(narrowest, road.width)
  }
  return Number.isFinite(narrowest) ? narrowest : 0
}

/**
 * The road that runs along one line of the grid.
 *
 * The lines of the district are the vertices of the road graph, so naming a
 * road by its orientation and the line it runs along is what lets the placer
 * walk from one road to the next at an intersection.
 *
 * @param network the district
 * @param orientation which axis the road runs along
 * @param lineIndex index of the line it runs along
 * @returns the road, or `null` when the line is outside the district
 */
export function gridRoad(
  network: RoadNetwork,
  orientation: RoadOrientation,
  lineIndex: number,
): Road | null {
  if (lineIndex < 0 || lineIndex > network.cells) {
    return null
  }
  const index = orientation === 'vertical' ? lineIndex : network.xLines.length + lineIndex
  return network.roads[index] ?? null
}
