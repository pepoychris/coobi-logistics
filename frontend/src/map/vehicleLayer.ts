/**
 * The mini vehicles of the map, kept apart from three.js.
 *
 * three.js is not what makes this interesting; the bookkeeping is. A node is
 * created for a vehicle the first time it is drawn, moved and re-dressed while
 * it stays drawn, and removed when it leaves the fleet or the budget. The scene
 * itself is never rebuilt: a tick costs what changed, a change of the visible
 * count costs the vehicles that entered or left the budget, and zooming costs
 * an update of every node and the creation of nothing.
 *
 * The layer takes the split of the fleet as it was decided rather than deciding
 * it, so the budget has one owner ({@link splitFleet}) and this class has one
 * job: making what is drawn match what was reported.
 */

import type { Vehicle } from '../api/types'
import type { FleetSplit } from './budget'

/** What one drawn vehicle is told about the view. */
export interface VehicleNodeState {
  /** Whether this is the vehicle the reader selected. */
  selected: boolean
  /** Size of the mini vehicle in world metres, which is what fixes its size on screen. */
  scale: number
  /** Whether the trails of the fleet are drawn. */
  trails: boolean
  /** Where the vehicle is, in the local metres of the map. */
  x: number
  y: number
}

/** One drawn mini vehicle, as this layer needs it. */
export interface VehicleNode {
  /** Moves, turns and re-dresses the node when a value it draws changed. */
  update(vehicle: Vehicle, state: VehicleNodeState): void
  remove(): void
}

/** Creates the node of a vehicle that enters the drawn half of the fleet. */
export interface VehicleNodeFactory {
  create(vehicle: Vehicle, state: VehicleNodeState): VehicleNode
}

/** What one call to {@link VehicleLayer.sync} did. */
export interface VehicleLayerStats {
  /** Vehicles drawn after the sync. */
  rendered: number
  created: number
  updated: number
  removed: number
}

/** A node and the state it was last drawn from. */
interface TrackedVehicle {
  node: VehicleNode
  vehicle: Vehicle
  state: VehicleNodeState
}

/**
 * Whether the node of a vehicle would change if it were drawn again.
 *
 * Every value the mini vehicle draws is compared - position, heading, status
 * and speed, because the speed is what makes a moving vehicle look busy - and
 * `lastUpdate` and `updatedAt` are deliberately left out: they change on every
 * event of a vehicle that is standing still as well, and redrawing for them
 * would make a tick cost the whole fleet.
 */
function isSameState(previous: Vehicle, next: Vehicle): boolean {
  return (
    previous.latitude === next.latitude &&
    previous.longitude === next.longitude &&
    previous.speed === next.speed &&
    previous.heading === next.heading &&
    previous.status === next.status
  )
}

function isSameView(previous: VehicleNodeState, next: VehicleNodeState): boolean {
  return (
    previous.selected === next.selected &&
    previous.scale === next.scale &&
    previous.trails === next.trails &&
    previous.x === next.x &&
    previous.y === next.y
  )
}

export class VehicleLayer {
  private readonly nodes = new Map<string, TrackedVehicle>()
  private readonly factory: VehicleNodeFactory

  constructor(factory: VehicleNodeFactory) {
    this.factory = factory
  }

  /** Vehicles the map is currently drawing. */
  get size(): number {
    return this.nodes.size
  }

  /**
   * Makes the drawn mini vehicles match the split of the fleet.
   *
   * @param split the fleet, already divided into what is drawn and what is not
   * @param stateOf how the view wants one node dressed; it depends on the
   *        vehicle, because only the selected one is drawn as selected
   * @returns what the call created, updated and removed
   */
  sync(split: FleetSplit, stateOf: (vehicle: Vehicle) => VehicleNodeState): VehicleLayerStats {
    let created = 0
    let updated = 0
    let removed = 0

    const drawn = new Set<string>()
    for (const vehicle of split.rendered) {
      drawn.add(vehicle.vehicleId)
      const state = stateOf(vehicle)
      const tracked = this.nodes.get(vehicle.vehicleId)
      if (!tracked) {
        this.nodes.set(vehicle.vehicleId, { node: this.factory.create(vehicle, state), vehicle, state })
        created += 1
        continue
      }
      if (isSameState(tracked.vehicle, vehicle) && isSameView(tracked.state, state)) {
        continue
      }
      tracked.node.update(vehicle, state)
      tracked.vehicle = vehicle
      tracked.state = state
      updated += 1
    }

    for (const [vehicleId, tracked] of [...this.nodes]) {
      if (!drawn.has(vehicleId)) {
        tracked.node.remove()
        this.nodes.delete(vehicleId)
        removed += 1
      }
    }

    return { rendered: this.nodes.size, created, updated, removed }
  }

  /** Removes every mini vehicle, for a view that is going away. */
  clear(): void {
    for (const tracked of this.nodes.values()) {
      tracked.node.remove()
    }
    this.nodes.clear()
  }

  /** Vehicle ids currently drawn, in insertion order. */
  ids(): string[] {
    return [...this.nodes.keys()]
  }
}
