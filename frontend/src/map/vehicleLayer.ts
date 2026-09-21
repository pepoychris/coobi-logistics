import type { Vehicle, VehicleStatus } from '../api/types'

/**
 * The markers of the fleet, kept apart from the map library.
 *
 * Leaflet is not what makes this interesting; the bookkeeping is. A marker is
 * created for a vehicle the first time it is seen, moved and re-labelled while
 * it exists, and removed when the backend stops reporting it - the map itself is
 * laid out once and never rebuilt. That rule lives here, behind a factory, so it
 * is exercised by a test with a recording factory rather than by a browser.
 */

/** One marker, as this layer needs it: a position, a status and a popup. */
export interface VehicleMarker {
  setPosition(latitude: number, longitude: number): void
  /** Re-draws the marker when the movement status of the vehicle changed. */
  setStatus(status: VehicleStatus): void
  /** Replaces the content the marker shows when it is opened. */
  setDetails(vehicle: Vehicle): void
  remove(): void
}

/** Creates the marker of a vehicle that is seen for the first time. */
export interface VehicleMarkerFactory {
  create(vehicle: Vehicle): VehicleMarker
}

/** A marker and the state it was last drawn from. */
interface TrackedVehicle {
  marker: VehicleMarker
  vehicle: Vehicle
}

/**
 * Whether the marker of a vehicle would change if it were drawn again.
 *
 * Heading is not compared because no part of the marker draws it, and the two
 * timestamps of the contract are compared only through `lastUpdate`: it is the
 * telemetry instant the popup shows.
 */
function isSameState(previous: Vehicle, next: Vehicle): boolean {
  return (
    previous.latitude === next.latitude &&
    previous.longitude === next.longitude &&
    previous.speed === next.speed &&
    previous.status === next.status &&
    previous.lastUpdate === next.lastUpdate
  )
}

export class VehicleLayer {
  private readonly markers = new Map<string, TrackedVehicle>()
  private readonly factory: VehicleMarkerFactory

  constructor(factory: VehicleMarkerFactory) {
    this.factory = factory
  }

  /** Vehicles the map is currently drawing. */
  get size(): number {
    return this.markers.size
  }

  /**
   * Makes the drawn markers match the fleet that was reported.
   *
   * A marker is created for a vehicle that was not drawn before, and an existing
   * one is touched only when a value it draws changed. Everything else is left
   * alone, which is what makes a tick cost the number of vehicles that moved
   * rather than the size of the fleet.
   *
   * @param vehicles the fleet as the backend last reported it
   */
  sync(vehicles: readonly Vehicle[]): void {
    const reported = new Set<string>()

    for (const vehicle of vehicles) {
      reported.add(vehicle.vehicleId)
      const tracked = this.markers.get(vehicle.vehicleId)
      if (!tracked) {
        this.markers.set(vehicle.vehicleId, { marker: this.factory.create(vehicle), vehicle })
        continue
      }
      if (isSameState(tracked.vehicle, vehicle)) {
        continue
      }
      tracked.marker.setPosition(vehicle.latitude, vehicle.longitude)
      tracked.marker.setStatus(vehicle.status)
      tracked.marker.setDetails(vehicle)
      tracked.vehicle = vehicle
    }

    for (const [vehicleId, tracked] of [...this.markers]) {
      if (!reported.has(vehicleId)) {
        tracked.marker.remove()
        this.markers.delete(vehicleId)
      }
    }
  }

  /** Removes every marker, for a view that is going away. */
  clear(): void {
    for (const tracked of this.markers.values()) {
      tracked.marker.remove()
    }
    this.markers.clear()
  }

  /** Vehicle ids currently drawn, in insertion order. */
  ids(): string[] {
    return [...this.markers.keys()]
  }
}
