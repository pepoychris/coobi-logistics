/**
 * Where the vehicles of the map are allowed to be: on the roads, and only on
 * the roads.
 *
 * The backend reports a position for every vehicle and nothing about the
 * streets it is driving through, so the two have to be reconciled here. The rule
 * of this module is that the fleet is placed on the road graph of the district
 * and nowhere else: a vehicle is projected onto the nearest lane of the nearest
 * road, and when that piece of lane is already taken it moves - always the same
 * way for the same fleet - to the next free slot, first along the road it is on
 * and then through the intersections of the graph, until it finds a place a
 * vehicle can legally be.
 *
 * Three properties are what keep the result readable and honest:
 *
 * - a slot of a lane holds one vehicle at most, so a fleet that reports several
 *   hundred vehicles at the same point spreads along the streets of the district
 *   instead of stacking into a single pile over an intersection;
 * - a vehicle keeps to the lane of the direction it is travelling in, so the
 *   traffic of a road is drawn on two sides of it and every vehicle of the map
 *   points the way it is driving;
 * - nothing here reads a clock, a random number or the previous placement: the
 *   same fleet over the same district always produces the same placements, which
 *   is what makes the map testable and what keeps a vehicle from wandering
 *   between two positions while the fleet around it stands still.
 */

import { stableHash } from './budget'
import {
  headingOfRoad,
  lanesInDirection,
  nearestRoad,
  normalizeHeading,
  pointOnRoad,
  roadLength,
  type Road,
  type RoadNetwork,
  type RoadOrientation,
} from './roadNetwork'

/** Length of one slot of a lane, in metres: a delivery van and the gap in front of it. */
export const SLOT_LENGTH_METERS = 14

/** Most slots one vehicle tests before it gives up and shares the one it reported. */
export const MAX_SLOT_PROBES = 800

/**
 * Most slots the seating of one whole fleet may test.
 *
 * Seating a vehicle is a handful of probes when the road it reported has room,
 * but a district that is already full makes every later vehicle walk the graph
 * to find that out. A fleet of a few hundred over a district sized to it never
 * comes close to this; the bound is there so that a fleet of tens of thousands
 * over a small district degrades into sharing slots - which is still legal
 * ground, and still deterministic - instead of freezing the browser while it
 * proves the district is full.
 */
export const MAX_SLOT_PROBES_PER_PLAN = 250_000

/** Most intersections one vehicle may cross while it looks for a free slot. */
export const MAX_ROUTE_HOPS = 8

/** One vehicle of the fleet, projected into the local metres of the map. */
export interface TrafficEntry {
  vehicleId: string
  x: number
  y: number
  /** Heading the telemetry reported, in degrees clockwise from north, when it reported one. */
  heading?: number
}

/** Where one vehicle of the fleet is drawn, and on what piece of road. */
export interface TrafficPlacement {
  x: number
  y: number
  /** Heading of the vehicle along its lane, in degrees clockwise from north. */
  heading: number
  /** Index of the road in the district, or `-1` for a district with no road at all. */
  roadIndex: number
  lane: number
  /** Metres from the first end of the road. */
  along: number
  /** Index of the slot of that lane the vehicle occupies. */
  slot: number
  /**
   * Whether the district was full and the vehicle is sharing its slot.
   *
   * It only happens when a district has more vehicles than the road network has
   * room for - far more than the budget of the map draws - and it is reported
   * rather than hidden so that a caller can say so.
   */
  shared: boolean
}

/** What the walk of the road graph needs to know about a junction. */
interface RouteState {
  /** Index of the vertical line the vehicle is on or travelling along. */
  vertical: number
  /** Index of the horizontal line it is on or travelling along. */
  horizontal: number
  /** Which axis it is travelling along. */
  along: RoadOrientation
  /** Which way it goes: `+1` towards the growing coordinates of the axis it travels. */
  sign: 1 | -1
}

/** One road of the route a vehicle takes when the road it reported is full. */
interface RouteStep {
  roadIndex: number
  lane: number
  /** Slot to start looking at: the end of that road the vehicle enters it by. */
  entrySlot: number
}

/**
 * The slots of the lanes of a district, and which of them are taken.
 *
 * It is a grid of one byte per slot of lane - a few kilobytes for a district -
 * so a fleet of thousands can be seated in one pass without allocating anything
 * per vehicle.
 */
class LaneOccupancy {
  private readonly taken = new Map<string, Uint8Array>()
  private readonly slots = new Map<number, number>()
  private readonly network: RoadNetwork
  private remaining: number

  constructor(network: RoadNetwork, budget: number = MAX_SLOT_PROBES_PER_PLAN) {
    this.network = network
    this.remaining = Math.max(0, Math.floor(budget))
  }

  /** How many slots a road has, on each of its lanes. */
  slotCount(roadIndex: number): number {
    const cached = this.slots.get(roadIndex)
    if (cached !== undefined) {
      return cached
    }
    const road = this.network.roads[roadIndex]
    const count = road ? Math.max(1, Math.ceil(roadLength(road) / SLOT_LENGTH_METERS)) : 0
    this.slots.set(roadIndex, count)
    return count
  }

  /**
   * @param roadIndex index of the road
   * @param along distance from its first end, in metres
   * @returns the slot of the lane that distance falls in
   */
  slotOf(roadIndex: number, along: number): number {
    const count = this.slotCount(roadIndex)
    return Math.min(count - 1, Math.max(0, Math.floor(along / SLOT_LENGTH_METERS)))
  }

  /**
   * The first free slot of a lane, looking outwards from a slot.
   *
   * Looking both ways matters: a fleet that reports its vehicles in a small
   * area fills the lane around them rather than piling them at one end of it.
   *
   * @param roadIndex index of the road
   * @param lane index of the lane
   * @param from slot to start at
   * @returns the free slot, or `null` when the lane has none left
   */
  firstFree(roadIndex: number, lane: number, from: number): number | null {
    if (this.remaining <= 0) {
      return null
    }
    const row = this.row(roadIndex, lane)
    const start = Math.min(row.length - 1, Math.max(0, from))
    const budget = Math.min(MAX_SLOT_PROBES, this.remaining)
    let probes = 0
    for (let distance = 0; distance < row.length && probes < budget; distance += 1) {
      const candidates = distance === 0 ? [start] : [start + distance, start - distance]
      for (const candidate of candidates) {
        probes += 1
        if (candidate >= 0 && candidate < row.length && row[candidate] === 0) {
          this.remaining -= probes
          return candidate
        }
        if (probes >= budget) {
          break
        }
      }
    }
    this.remaining -= probes
    return null
  }

  /** Marks a slot as taken by the vehicle that is being seated in it. */
  occupy(roadIndex: number, lane: number, slot: number): void {
    const row = this.row(roadIndex, lane)
    if (slot >= 0 && slot < row.length) {
      row[slot] = 1
    }
  }

  private row(roadIndex: number, lane: number): Uint8Array {
    const key = `${roadIndex}:${lane}`
    let row = this.taken.get(key)
    if (!row) {
      row = new Uint8Array(this.slotCount(roadIndex))
      this.taken.set(key, row)
    }
    return row
  }
}

/**
 * Seats a whole fleet on the roads of a district.
 *
 * The order is fixed - by the fingerprint of the identifier, and by the
 * identifier itself to break a tie - so the map draws the same fleet in the same
 * place on any machine, and the vehicles the renderer picks first are the ones
 * seated first.
 *
 * @param network the district to place the fleet in
 * @param entries the fleet, in the local metres of the map
 * @returns one placement per entry, keyed by vehicle identifier
 */
export function planTraffic(
  network: RoadNetwork,
  entries: readonly TrafficEntry[],
): Map<string, TrafficPlacement> {
  const occupancy = new LaneOccupancy(network)
  const placements = new Map<string, TrafficPlacement>()
  const order = [...entries].sort((left, right) => compareVehicles(left.vehicleId, right.vehicleId))
  for (const entry of order) {
    placements.set(entry.vehicleId, seatVehicle(network, occupancy, entry))
  }
  return placements
}

/**
 * @param left identifier of one vehicle
 * @param right identifier of another
 * @returns which of the two is seated first, always the same way round
 */
function compareVehicles(left: string, right: string): number {
  const difference = stableHash(left) - stableHash(right)
  return difference !== 0 ? difference : left.localeCompare(right)
}

/**
 * Puts one vehicle on the nearest legal piece of road.
 *
 * @param network the district
 * @param occupancy the slots already taken
 * @param entry the vehicle, in local metres
 * @returns where it is drawn
 */
function seatVehicle(
  network: RoadNetwork,
  occupancy: LaneOccupancy,
  entry: TrafficEntry,
): TrafficPlacement {
  const hit = nearestRoad(network, { x: entry.x, y: entry.y })
  if (!hit) {
    return {
      x: entry.x,
      y: entry.y,
      heading: normalizeHeading(entry.heading ?? 0),
      roadIndex: -1,
      lane: 0,
      along: 0,
      slot: 0,
      shared: true,
    }
  }

  const road = network.roads[hit.roadIndex]
  const forward = travelsForward(road, entry)
  const lane = laneOnSide(road, forward, entry.vehicleId, 0)
  const reported = occupancy.slotOf(hit.roadIndex, hit.along)

  const slot = occupancy.firstFree(hit.roadIndex, lane, reported)
  if (slot !== null) {
    occupancy.occupy(hit.roadIndex, lane, slot)
    return placementOn(network, hit.roadIndex, lane, slot, false)
  }

  // The lane the vehicle reported is full: it drives on, through the
  // intersections of the graph, and is seated on the first road with room.
  for (const step of routeSteps(network, hit.roadIndex, forward, entry.vehicleId)) {
    const stepSlot = occupancy.firstFree(step.roadIndex, step.lane, step.entrySlot)
    if (stepSlot === null) {
      continue
    }
    occupancy.occupy(step.roadIndex, step.lane, stepSlot)
    return placementOn(network, step.roadIndex, step.lane, stepSlot, false)
  }

  // Nothing left anywhere: the district carries more vehicles than it has road,
  // so the vehicle keeps its own road and shares the slot it reported.
  return placementOn(network, hit.roadIndex, lane, reported, true)
}

/**
 * @param road one road of the district
 * @param entry one vehicle
 * @returns whether it travels the road from its first end to its second
 */
function travelsForward(road: Road, entry: TrafficEntry): boolean {
  const heading = entry.heading
  if (typeof heading !== 'number' || !Number.isFinite(heading)) {
    // Nothing to align with: half the fleet each way, decided by its identifier.
    return stableHash(entry.vehicleId) % 2 === 0
  }
  const difference = Math.abs(normalizeHeading(heading) - headingOfRoad(road, true))
  return Math.abs(((difference + 180) % 360) - 180) <= 90
}

/**
 * Picks one of the lanes that carry the direction a vehicle is travelling in.
 *
 * @param road one road of the district
 * @param forward whether the vehicle travels it forwards
 * @param vehicleId identifier of the vehicle
 * @param salt number that makes a second choice on the same road a different one
 * @returns the index of the lane
 */
function laneOnSide(road: Road, forward: boolean, vehicleId: string, salt: number): number {
  const half = lanesInDirection(road)
  const pick = (stableHash(vehicleId) + Math.imul(salt + 1, 0x9e3779b1)) >>> 0
  const position = pick % half
  return forward ? half + position : half - 1 - position
}

/**
 * The roads a vehicle drives through when the one it reported has no room.
 *
 * The walk visits junctions of the grid: at each of them the vehicle carries
 * straight on unless the deterministic choice of its identifier tells it to turn
 * onto the road that crosses there, which is what keeps its detour a route
 * through the graph of the district rather than a jump to an arbitrary street.
 * The first hop starts at the end of the road the vehicle entered, because the
 * whole of that road has just been tried.
 *
 * @param network the district
 * @param startRoadIndex road the vehicle reported
 * @param forward whether it travels that road forwards
 * @param vehicleId identifier of the vehicle, which is the only seed of the walk
 * @returns the roads to try, in the order the vehicle would reach them
 */
function routeSteps(
  network: RoadNetwork,
  startRoadIndex: number,
  forward: boolean,
  vehicleId: string,
): RouteStep[] {
  const start = network.roads[startRoadIndex]
  const steps: RouteStep[] = []
  const visited = new Set<string>()
  const seed = stableHash(vehicleId)
  let state: RouteState = {
    vertical: start.orientation === 'vertical' ? start.lineIndex : 0,
    horizontal: start.orientation === 'horizontal' ? start.lineIndex : 0,
    along: start.orientation,
    sign: forward ? 1 : -1,
  }

  // The far end of the road the vehicle is on, which is where its detour starts.
  if (state.along === 'vertical') {
    state = { ...state, horizontal: forward ? network.cells : 0 }
  } else {
    state = { ...state, vertical: forward ? network.cells : 0 }
  }

  const junctions = (network.cells + 1) * 2
  for (let junction = 0; junction < junctions && steps.length < MAX_ROUTE_HOPS; junction += 1) {
    const cross = state.along === 'vertical' ? state.horizontal : state.vertical
    const next = cross + state.sign
    const canContinue = next >= 0 && next <= network.cells
    const turns = !canContinue || (seed + Math.imul(junction + 1, 0x85ebca6b)) % 2 === 0

    if (!turns) {
      state =
        state.along === 'vertical'
          ? { ...state, horizontal: next }
          : { ...state, vertical: next }
      continue
    }

    const turn: RouteState = { ...state, along: state.along === 'vertical' ? 'horizontal' : 'vertical' }
    turn.sign = ((seed + Math.imul(junction + 3, 0xc2b2ae35)) % 2 === 0 ? 1 : -1) as 1 | -1
    state = turn

    const road = gridRoadIndex(network, state.along, lineOf(state))
    if (road < 0) {
      break
    }
    const stepLane = laneOnSide(network.roads[road], state.sign > 0, vehicleId, steps.length + 1)
    const key = `${road}:${stepLane}`
    if (visited.has(key)) {
      continue
    }
    visited.add(key)
    steps.push({ roadIndex: road, lane: stepLane, entrySlot: entrySlotOf(network, state) })
  }
  return steps
}

/**
 * @param state where a vehicle is on the grid of junctions
 * @returns the line its road runs along, in the axis of that road
 */
function lineOf(state: RouteState): number {
  return state.along === 'vertical' ? state.vertical : state.horizontal
}

/**
 * @param network the district
 * @param orientation the axis the road the vehicle has just turned onto runs along
 * @param lineIndex index of the line it runs along
 * @returns the index of that road in the district, or `-1` when there is none
 */
function gridRoadIndex(network: RoadNetwork, orientation: RoadOrientation, lineIndex: number): number {
  if (lineIndex < 0 || lineIndex > network.cells) {
    return -1
  }
  return orientation === 'vertical' ? lineIndex : network.xLines.length + lineIndex
}

/**
 * The slot a vehicle enters a road by, which is the junction it turned at.
 *
 * @param network the district
 * @param state where the vehicle is on the grid of junctions
 * @returns the slot of the road nearest that junction
 */
function entrySlotOf(network: RoadNetwork, state: RouteState): number {
  const road = network.roads[gridRoadIndex(network, state.along, lineOf(state))]
  const junction = state.along === 'vertical' ? network.yLines[state.horizontal] : network.xLines[state.vertical]
  const along = junction + network.halfExtent
  if (!road) {
    return 0
  }
  const last = Math.max(0, Math.ceil(roadLength(road) / SLOT_LENGTH_METERS) - 1)
  return Math.min(Math.max(Math.floor(along / SLOT_LENGTH_METERS), 0), last)
}

/**
 * @param network the district
 * @param roadIndex the road a vehicle is seated on
 * @param lane the lane it keeps to
 * @param slot the slot it occupies
 * @param shared whether the district had no room left
 * @returns where the vehicle is drawn, at the centre of its slot
 */
function placementOn(
  network: RoadNetwork,
  roadIndex: number,
  lane: number,
  slot: number,
  shared: boolean,
): TrafficPlacement {
  const road = network.roads[roadIndex]
  const along = Math.min(roadLength(road), slot * SLOT_LENGTH_METERS + SLOT_LENGTH_METERS / 2)
  const position = pointOnRoad(road, along, lane)
  return {
    x: position.x,
    y: position.y,
    heading: position.heading,
    roadIndex,
    lane,
    along,
    slot,
    shared,
  }
}
