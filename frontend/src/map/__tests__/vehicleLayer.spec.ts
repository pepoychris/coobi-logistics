import { describe, expect, it } from 'vitest'

import { vehicle } from '../../__tests__/support/fixtures'
import type { Vehicle, VehicleStatus } from '../../api/types'
import { VehicleLayer } from '../vehicleLayer'
import type { VehicleMarker, VehicleMarkerFactory } from '../vehicleLayer'

/**
 * A marker that records what was done to it, which is how the incremental rule
 * of the layer is asserted: what was created, what was updated, what was
 * removed, and how many times.
 */
class RecordingMarker implements VehicleMarker {
  readonly positions: [number, number][] = []
  readonly statuses: VehicleStatus[] = []
  readonly details: Vehicle[] = []
  removed = false

  setPosition(latitude: number, longitude: number): void {
    this.positions.push([latitude, longitude])
  }

  setStatus(status: VehicleStatus): void {
    this.statuses.push(status)
  }

  setDetails(vehicle: Vehicle): void {
    this.details.push(vehicle)
  }

  remove(): void {
    this.removed = true
  }
}

function recordingLayer(): { layer: VehicleLayer; created: RecordingMarker[]; byId: Map<string, RecordingMarker> } {
  const created: RecordingMarker[] = []
  const byId = new Map<string, RecordingMarker>()
  const factory: VehicleMarkerFactory = {
    create: () => {
      const marker = new RecordingMarker()
      created.push(marker)
      return marker
    },
  }
  return { layer: new VehicleLayer(factory), created, byId }
}

describe('VehicleLayer', () => {
  it('creates one marker per vehicle the first time the fleet is drawn', () => {
    const { layer, created } = recordingLayer()

    layer.sync([vehicle({ vehicleId: 'TRUCK-1' }), vehicle({ vehicleId: 'TRUCK-2' })])

    expect(created).toHaveLength(2)
    expect(layer.size).toBe(2)
    expect(layer.ids()).toEqual(['TRUCK-1', 'TRUCK-2'])
  })

  it('moves the marker that exists instead of creating a second one', () => {
    const { layer, created } = recordingLayer()

    layer.sync([vehicle({ vehicleId: 'TRUCK-1', latitude: 39.4, longitude: -0.3 })])
    const first = created[0]
    layer.sync([vehicle({ vehicleId: 'TRUCK-1', latitude: 39.5, longitude: -0.4, status: 'STOPPED' })])

    expect(created).toHaveLength(1)
    expect(first.positions).toEqual([[39.5, -0.4]])
    expect(first.statuses).toEqual(['STOPPED'])
    expect(first.details).toHaveLength(1)
    expect(first.details[0].status).toBe('STOPPED')
  })

  it('leaves a marker alone while the state it draws does not change', () => {
    const { layer, created } = recordingLayer()
    const fleet = [vehicle({ vehicleId: 'TRUCK-1' }), vehicle({ vehicleId: 'TRUCK-2' })]

    layer.sync(fleet)
    layer.sync([...fleet])

    expect(created).toHaveLength(2)
    expect(created.flatMap((marker) => marker.positions)).toEqual([])
    expect(created.flatMap((marker) => marker.statuses)).toEqual([])
    expect(created.flatMap((marker) => marker.details)).toEqual([])
  })

  it('adds a marker only for a vehicle it has not seen', () => {
    const { layer, created } = recordingLayer()

    layer.sync([vehicle({ vehicleId: 'TRUCK-1' })])
    layer.sync([vehicle({ vehicleId: 'TRUCK-1' }), vehicle({ vehicleId: 'TRUCK-2' })])

    expect(created).toHaveLength(2)
    expect(layer.ids()).toEqual(['TRUCK-1', 'TRUCK-2'])
  })

  it('removes the marker of a vehicle the backend stopped reporting', () => {
    const { layer, created } = recordingLayer()

    layer.sync([vehicle({ vehicleId: 'TRUCK-1' }), vehicle({ vehicleId: 'TRUCK-2' })])
    layer.sync([vehicle({ vehicleId: 'TRUCK-2' })])

    expect(created[0].removed).toBe(true)
    expect(created[1].removed).toBe(false)
    expect(layer.ids()).toEqual(['TRUCK-2'])
  })

  it('removes every marker when it is cleared', () => {
    const { layer, created } = recordingLayer()

    layer.sync([vehicle({ vehicleId: 'TRUCK-1' }), vehicle({ vehicleId: 'TRUCK-2' })])
    layer.clear()

    expect(created.every((marker) => marker.removed)).toBe(true)
    expect(layer.size).toBe(0)
  })
})
