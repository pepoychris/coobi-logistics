import { describe, expect, it } from 'vitest'

import { vehicle } from '../../__tests__/support/fixtures'
import type { Vehicle } from '../../api/types'
import { MAX_RENDERED_VEHICLES } from '../budget'
import {
  FleetPresenter,
  MAX_VEHICLE_ROAD_SHARE,
  TRAIL_POINTS,
  type FleetRenderer,
  type GroundLook,
  type VehicleTrail,
  type ViewportSize,
} from '../fleetPresenter'
import type { FleetView, LocalPoint } from '../projection'
import { insideBuilding, narrowestRoadWidth, onRoadSurface } from '../roadNetwork'
import type { VehicleNode, VehicleNodeState } from '../vehicleLayer'
import { VEHICLE_LENGTH_METERS } from '../vehicleMesh'

/**
 * A stand-in for the renderer that records what the map asked it to do.
 *
 * It is what lets the rules of the map - the budget, the incremental update,
 * the trails, the camera - be checked deterministically and offline, which a
 * GPU could not be.
 */
class RecordingRenderer implements FleetRenderer {
  readonly nodes = new Map<string, { updates: number; removed: boolean; state: VehicleNodeState }>()
  created = 0
  clouds: { points: readonly LocalPoint[]; visible: boolean; sizePixels: number }[] = []
  trails: { list: readonly VehicleTrail[]; scale: number }[] = []
  views: FleetView[] = []
  resizes: ViewportSize[] = []
  grounds: GroundLook[] = []
  disposals = 0

  createNode(vehicle: Vehicle, state: VehicleNodeState): VehicleNode {
    this.created += 1
    const record = { updates: 0, removed: false, state }
    this.nodes.set(vehicle.vehicleId, record)
    return {
      update: (_next, nextState) => {
        record.updates += 1
        record.state = nextState
      },
      remove: () => {
        record.removed = true
      },
    }
  }

  drawCloud(points: readonly LocalPoint[], options: { visible: boolean; sizePixels: number }): void {
    this.clouds.push({ points: [...points], visible: options.visible, sizePixels: options.sizePixels })
  }

  drawTrails(list: readonly VehicleTrail[], scale: number): void {
    this.trails.push({ list: list.map((trail) => ({ ...trail, points: [...trail.points] })), scale })
  }

  setView(view: FleetView, size: ViewportSize): void {
    this.views.push(view)
    this.resizes.push(size)
  }

  drawGround(look: GroundLook): void {
    this.grounds.push(look)
  }

  resize(size: ViewportSize): void {
    this.resizes.push(size)
  }

  dispose(): void {
    this.disposals += 1
  }

  get lastCloud() {
    return this.clouds[this.clouds.length - 1]
  }

  get lastTrails() {
    return this.trails[this.trails.length - 1]
  }
}

/** A fleet spread over a few kilometres, so fitting and picking mean something. */
function fleet(count: number, status: Vehicle['status'] = 'MOVING'): Vehicle[] {
  return Array.from({ length: count }, (_, index) =>
    vehicle({
      vehicleId: `TRUCK-${String(index).padStart(3, '0')}`,
      latitude: 39.4699 + (index % 20) * 0.0015,
      longitude: -0.3763 + Math.floor(index / 20) * 0.0015,
      status,
    }),
  )
}

function presenter(renderer: RecordingRenderer, options = {}): FleetPresenter {
  const instance = new FleetPresenter(renderer, options)
  instance.setSize({ width: 900, height: 600 })
  return instance
}

describe('the presenter of the fleet map', () => {
  it('creates the ground once, and never a second scene', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer)

    expect(renderer.grounds).toHaveLength(1)
    expect(renderer.disposals).toBe(0)

    map.update(fleet(30))
    map.setOptions({ visibleCount: 100, trails: false, themeId: 'blueprint' })

    // The palette is repainted on the ground that is already there; the scene
    // is never disposed and built again.
    expect(renderer.disposals).toBe(0)
    expect(renderer.grounds.length).toBeGreaterThan(1)
    expect(renderer.grounds[renderer.grounds.length - 1].themeId).toBe('blueprint')
  })

  it('draws what the budget allows and hands the rest to the faint cloud', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 50 })

    const result = map.update(fleet(180))

    expect(result.rendered).toBe(50)
    expect(renderer.created).toBe(50)
    expect(renderer.lastCloud?.points).toHaveLength(130)
    expect(renderer.lastCloud?.visible).toBe(true)
    expect(map.counts).toEqual({ tracked: 180, rendered: 50, background: 130, omitted: 0 })
  })

  it('keeps the ceiling of the map whatever a caller asks for', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 5_000 })

    map.update(fleet(2_000))

    expect(renderer.created).toBe(MAX_RENDERED_VEHICLES)
    expect(map.currentOptions.visibleCount).toBe(MAX_RENDERED_VEHICLES)
  })

  it('adds the vehicles that enter the budget, and keeps the nodes that were already drawn', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 25 })
    const drawn = fleet(150)
    map.update(drawn)
    const createdFirst = renderer.created

    map.setOptions({ visibleCount: 100 })

    expect(renderer.created).toBe(createdFirst + 75)
    expect(renderer.disposals).toBe(0)
  })

  it('leaves the fleet alone when only the camera changes', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 20 })
    map.update(fleet(20))
    const created = renderer.created
    const updates = [...renderer.nodes.values()].reduce((total, node) => total + node.updates, 0)

    // Close enough in that a mini vehicle is no longer capped by the width of
    // the street it drives down, so the camera does change how it is drawn.
    map.zoomAt(0, 0, 0.2)
    map.panBy(40, -20)

    expect(renderer.created).toBe(created)
    expect(renderer.disposals).toBe(0)
    // Zooming and panning re-dress what is drawn; they never rebuild it.
    expect([...renderer.nodes.values()].reduce((total, node) => total + node.updates, 0)).toBeGreaterThan(updates)
  })

  it('draws every mini vehicle on a road of the district, and none on a building', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 100 })
    // A fleet that reports itself inside one block, which is what a cluster of
    // vehicles looks like when the telemetry of a whole depot is read at once.
    const crowded = Array.from({ length: 240 }, (_, index) =>
      vehicle({
        vehicleId: `TRUCK-${String(index).padStart(3, '0')}`,
        latitude: 39.4699 + (index % 3) * 0.00002,
        longitude: -0.3763 + (index % 5) * 0.00002,
      }),
    )

    map.update(crowded)

    const district = map.currentDistrict
    const states = [...renderer.nodes.values()].map((node) => node.state)
    expect(states).toHaveLength(100)
    // A mini vehicle is never drawn longer than a fraction of the narrowest
    // street of the district, so the city never looks like it is made of vans.
    const cap = Math.max(VEHICLE_LENGTH_METERS, narrowestRoadWidth(district) * MAX_VEHICLE_ROAD_SHARE)
    for (const state of states) {
      expect(onRoadSurface(district, state)).toBe(true)
      expect(insideBuilding(district, state)).toBe(false)
      expect(state.scale).toBeLessThanOrEqual(cap)
    }
    // And the cloud outside the budget is on the roads as well.
    for (const point of renderer.lastCloud?.points ?? []) {
      expect(onRoadSurface(district, point)).toBe(true)
    }
  })

  it('fits the whole fleet in the view when it follows', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer)
    const drawn = fleet(60)

    map.update(drawn)
    const view = map.currentView

    expect(view).not.toBeNull()
    expect(renderer.views.length).toBeGreaterThan(0)
    expect(view!.halfWidth).toBeGreaterThan(view!.halfHeight)
  })

  it('leaves the camera where a reader put it when following is off', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { followFleet: false })

    map.update(fleet(10))

    expect(map.currentView).toBeNull()
    expect(renderer.views).toHaveLength(0)
  })

  it('picks the vehicle under a pointer and nothing under the bare ground', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer)
    const drawn = fleet(40)
    map.update(drawn)
    const view = map.currentView!
    const target = drawn[7]
    const point = map.pick(0, 0, 0)

    // The pixel of the vehicle, computed through the same view the map draws.
    const state = renderer.nodes.get(target.vehicleId)!.state
    const offsetX = ((state.x - (view.centerX - view.halfWidth)) / (view.halfWidth * 2)) * 900
    const offsetY = ((view.centerY + view.halfHeight - state.y) / (view.halfHeight * 2)) * 600

    expect(point).toBeNull()
    expect(map.pick(offsetX, offsetY)?.vehicleId).toBe(target.vehicleId)
    expect(map.pick(offsetX + 300, offsetY + 300)).toBeNull()
  })

  it('draws the vehicle a reader selected, even outside the budget', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 10 })
    const drawn = fleet(120)
    map.update(drawn)
    const outside = drawn.find((entry) => !renderer.nodes.has(entry.vehicleId))!

    map.setSelection(outside.vehicleId)

    expect(renderer.nodes.get(outside.vehicleId)?.state.selected).toBe(true)
    expect(map.selectedVehicleId).toBe(outside.vehicleId)
  })

  it('keeps a bounded trail of the vehicles it draws, and none at all when trails are off', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 10, followFleet: false })
    map.update(fleet(30))

    // The fleet drives: every update moves it a little further east.
    for (let step = 1; step <= 20; step += 1) {
      map.update(fleet(30).map((entry) => ({ ...entry, longitude: entry.longitude + step * 0.0002 })))
    }

    const trails = renderer.lastTrails!.list
    expect(trails.length).toBeGreaterThan(0)
    expect(trails.every((trail) => trail.points.length <= TRAIL_POINTS)).toBe(true)
    expect(trails.some((trail) => trail.points.length > 1)).toBe(true)

    map.setOptions({ trails: false })

    expect(renderer.lastTrails!.list).toHaveLength(0)
  })

  it('forgets the cloud when a reader turns the faint fleet off', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 10 })
    map.update(fleet(40))

    map.setOptions({ backgroundFleet: false })

    expect(renderer.lastCloud?.visible).toBe(false)
    expect(renderer.lastCloud?.points).toHaveLength(0)
  })

  it('gives the fleet the same ground twice for the same positions', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer, { visibleCount: 10 })
    const drawn = fleet(25)

    map.update(drawn)
    const first = renderer.lastCloud?.points
    map.update(drawn)
    const second = renderer.lastCloud?.points

    expect(first).toHaveLength(15)
    expect(second).toEqual(first)
  })

  it('lays the district out again only when the fleet outgrows it', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer)
    map.update(fleet(25))
    const grounds = renderer.grounds.length

    map.update(fleet(25))

    expect(renderer.grounds).toHaveLength(grounds)

    map.update(
      fleet(25).map((entry) => ({ ...entry, latitude: entry.latitude + 0.4, longitude: entry.longitude + 0.4 })),
    )

    expect(renderer.grounds.length).toBeGreaterThan(grounds)
  })

  it('releases the renderer and its nodes when the panel goes away', () => {
    const renderer = new RecordingRenderer()
    const map = presenter(renderer)
    map.update(fleet(15))

    map.dispose()

    expect(renderer.disposals).toBe(1)
    expect([...renderer.nodes.values()].every((node) => node.removed)).toBe(true)
  })
})
