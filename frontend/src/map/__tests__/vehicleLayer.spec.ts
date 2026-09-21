import { describe, expect, it } from 'vitest'

import { vehicle } from '../../__tests__/support/fixtures'
import type { Vehicle } from '../../api/types'
import { splitFleet } from '../budget'
import { VehicleLayer, type VehicleNode, type VehicleNodeFactory, type VehicleNodeState } from '../vehicleLayer'

/**
 * A stand-in for a mini vehicle that records what the layer asked of it, so the
 * bookkeeping of the map is tested without a GPU.
 */
class RecordingNode implements VehicleNode {
  readonly id: string
  updates: { vehicle: Vehicle; state: VehicleNodeState }[] = []
  removed = false

  constructor(id: string) {
    this.id = id
  }

  update(vehicle: Vehicle, state: VehicleNodeState): void {
    this.updates.push({ vehicle, state })
  }

  remove(): void {
    this.removed = true
  }
}

class RecordingFactory implements VehicleNodeFactory {
  readonly created: RecordingNode[] = []
  readonly createdWith = new Map<string, VehicleNodeState>()

  create(vehicle: Vehicle, state: VehicleNodeState): VehicleNode {
    const node = new RecordingNode(vehicle.vehicleId)
    this.created.push(node)
    this.createdWith.set(vehicle.vehicleId, state)
    return node
  }

  get nodesByVehicle(): Map<string, RecordingNode> {
    return new Map(this.created.map((node) => [node.id, node]))
  }
}

const STATE: VehicleNodeState = { selected: false, scale: 1, trails: true, x: 0, y: 0 }

function stateOf(overrides: Partial<VehicleNodeState> = {}): (entry: Vehicle) => VehicleNodeState {
  return () => ({ ...STATE, ...overrides })
}

function fleet(count: number, status: Vehicle['status'] = 'MOVING'): Vehicle[] {
  return Array.from({ length: count }, (_, index) =>
    vehicle({ vehicleId: `TRUCK-${String(index).padStart(3, '0')}`, status }),
  )
}

describe('the layer of mini vehicles', () => {
  it('creates one node per drawn vehicle and touches nothing on a quiet tick', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    const drawn = fleet(3)

    const first = layer.sync(splitFleet(drawn, 10), stateOf())
    const second = layer.sync(splitFleet(drawn, 10), stateOf())

    expect(first).toEqual({ rendered: 3, created: 3, updated: 0, removed: 0 })
    expect(second).toEqual({ rendered: 3, created: 0, updated: 0, removed: 0 })
    expect(factory.created).toHaveLength(3)
  })

  it('moves only the vehicle whose state changed', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    const before = fleet(4)
    layer.sync(splitFleet(before, 10), stateOf())

    const after = before.map((entry, index) =>
      index === 2 ? { ...entry, latitude: entry.latitude + 0.001, speed: entry.speed + 5 } : entry,
    )
    const stats = layer.sync(splitFleet(after, 10), stateOf())

    expect(stats).toEqual({ rendered: 4, created: 0, updated: 1, removed: 0 })
    expect(factory.nodesByVehicle.get('TRUCK-002')?.updates).toHaveLength(1)
    expect(factory.nodesByVehicle.get('TRUCK-000')?.updates).toHaveLength(0)
  })

  it('removes the vehicle the backend stopped reporting', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    const before = fleet(3)
    layer.sync(splitFleet(before, 10), stateOf())

    const stats = layer.sync(splitFleet(before.slice(0, 2), 10), stateOf())

    expect(stats).toEqual({ rendered: 2, created: 0, updated: 0, removed: 1 })
    expect(factory.nodesByVehicle.get('TRUCK-002')?.removed).toBe(true)
    expect([...layer.ids()].sort()).toEqual(['TRUCK-000', 'TRUCK-001'])
  })

  it('draws no more nodes than the budget, however large the fleet is', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)

    const stats = layer.sync(splitFleet(fleet(2_000), 100), stateOf())

    expect(stats.created).toBe(100)
    expect(layer.size).toBe(100)
  })

  it('adds and removes the vehicles that cross the budget when a reader changes the count', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    const drawn = fleet(150)
    layer.sync(splitFleet(drawn, 25), stateOf())
    const firstTwentyFive = layer.ids()

    const grown = layer.sync(splitFleet(drawn, 100), stateOf())

    expect(grown.created).toBe(75)
    expect(grown.updated).toBe(0)
    expect(grown.removed).toBe(0)
    expect(layer.ids().slice(0, 25)).toEqual(firstTwentyFive)

    const shrunk = layer.sync(splitFleet(drawn, 25), stateOf())

    expect(shrunk.removed).toBe(75)
    expect(layer.size).toBe(25)
  })

  it('redraws every node when the camera changes the size of the fleet on screen, and creates none', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    const drawn = fleet(12)
    layer.sync(splitFleet(drawn, 25), stateOf({ scale: 2 }))

    const zoomed = layer.sync(splitFleet(drawn, 25), stateOf({ scale: 0.5 }))

    expect(zoomed).toEqual({ rendered: 12, created: 0, updated: 12, removed: 0 })
  })

  it('draws the vehicle a reader selected even when the budget left it out', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    const drawn = fleet(200, 'STOPPED')

    layer.sync(splitFleet(drawn, 10, 'TRUCK-199'), (entry) => ({
      ...STATE,
      selected: entry.vehicleId === 'TRUCK-199',
    }))

    expect(layer.ids()).toContain('TRUCK-199')
    expect(factory.createdWith.get('TRUCK-199')?.selected).toBe(true)
  })

  it('removes every node when the view goes away', () => {
    const factory = new RecordingFactory()
    const layer = new VehicleLayer(factory)
    layer.sync(splitFleet(fleet(5), 10), stateOf())

    layer.clear()

    expect(layer.size).toBe(0)
    expect(factory.created.every((node) => node.removed)).toBe(true)
  })
})
