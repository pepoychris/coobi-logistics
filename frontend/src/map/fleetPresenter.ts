/**
 * The map, without three.js in the room.
 *
 * Everything that decides what the fleet map shows lives here - which vehicles
 * are drawn in full and which are only acknowledged, where the camera is, which
 * trails exist, what a click selects, and when the procedural district has to
 * be laid out again. The renderer is an interface with one implementation that
 * talks to the GPU and one stand-in that records calls, so the rules of the map
 * are tested without a browser and the drawing code stays a thin translation of
 * them.
 *
 * Two of those rules are worth naming, because they are what keeps the map
 * usable while a fleet is large:
 *
 * - the fleet is split into a drawn half and a faint cloud by
 *   {@link splitFleet}, so the number of mini vehicles never exceeds
 *   {@link MAX_RENDERED_VEHICLES}, however many vehicles report;
 * - changing an option - the visible count, the palette, the trails - never
 *   recreates the scene. A palette change repaints ground that is already
 *   there, and a count change adds and removes the mini vehicles that crossed
 *   the budget, which is what the layer already knows how to do.
 */

import type { Vehicle } from '../api/types'
import { clampVisibleCount, DEFAULT_VISIBLE_COUNT, splitFleet, type FleetSplit } from './budget'
import {
  boundsOf,
  fitView,
  groundAt,
  metersPerPixel,
  panView,
  projectPoint,
  zoomView,
  type FleetView,
  type GeoPoint,
  type LocalPoint,
} from './projection'
import { networkCoversExtent, normalizeDensity, type NetworkDensity } from './roadNetwork'
import { DEFAULT_THEME_ID, isFleetThemeId, type FleetThemeId } from './theme'
import { VehicleLayer, type VehicleLayerStats, type VehicleNode, type VehicleNodeState } from './vehicleLayer'

/** Size of the drawing surface, in CSS pixels. */
export interface ViewportSize {
  width: number
  height: number
}

/** How many positions of a vehicle the trail keeps. */
export const TRAIL_POINTS = 14

/** How much of the view the district leaves around the fleet, as a factor. */
export const DISTRICT_MARGIN = 1.15

/** What the controls of the dashboard let a reader change. */
export interface FleetOptions {
  /** How many vehicles are drawn as their own mini vehicle. */
  visibleCount: number
  /** Whether the vehicles outside the budget are drawn as a faint cloud. */
  backgroundFleet: boolean
  /** Whether a moving vehicle leaves a trail behind it. */
  trails: boolean
  /** Whether the camera follows the fleet instead of staying where it was left. */
  followFleet: boolean
  /** How busy the street grid of the procedural district is. */
  density: NetworkDensity
  /** Palette of the map. */
  themeId: FleetThemeId
}

export const DEFAULT_FLEET_OPTIONS: FleetOptions = {
  visibleCount: DEFAULT_VISIBLE_COUNT,
  backgroundFleet: true,
  trails: true,
  followFleet: true,
  density: 'regular',
  themeId: DEFAULT_THEME_ID,
}

/** One vehicle's recent positions, for the trail it leaves. */
export interface VehicleTrail {
  vehicleId: string
  points: readonly LocalPoint[]
}

/** What the ground of the map is drawn with. */
export interface GroundLook {
  themeId: FleetThemeId
  density: NetworkDensity
  /** Half width of the district, in metres. A change of this lays it out again. */
  halfExtent: number
}

/**
 * What the map draws.
 *
 * The presenter owns every decision and calls these methods with values that
 * are already final, so an implementation is free to be as simple as it likes.
 */
export interface FleetRenderer {
  /** Creates the node of one vehicle that entered the drawn half of the fleet. */
  createNode(vehicle: Vehicle, state: VehicleNodeState): VehicleNode
  /** Replaces the faint cloud with the vehicles that are outside the budget. */
  drawCloud(points: readonly LocalPoint[], options: { visible: boolean }): void
  /** Replaces the trails of the fleet. `scale` is the world size of one drawn vehicle. */
  drawTrails(trails: readonly VehicleTrail[], scale: number): void
  /** Moves the camera and tells it how big the drawing surface is. */
  setView(view: FleetView, size: ViewportSize): void
  /** Repaints the ground; the district is laid out again only when it has to be. */
  drawGround(look: GroundLook): void
  /** Tells the renderer how big the drawing surface is, without moving the camera. */
  resize(size: ViewportSize): void
  /** Releases everything the renderer holds. */
  dispose(): void
}

/** What one update of the map did. */
export interface FleetUpdate extends VehicleLayerStats {
  /** Vehicles of the fleet the dashboard is tracking. */
  tracked: number
  /** Vehicles the renderer was told to draw as a faint cloud. */
  background: number
  /** Vehicles beyond the budget of the cloud. */
  omitted: number
}

/** What the panel around the map reports about the budget. */
export interface FleetCounts {
  tracked: number
  rendered: number
  background: number
  omitted: number
}

/**
 * @param partial options a caller suggests
 * @param base options to fall back on
 * @returns options that are all valid, whatever was suggested
 */
export function normalizeFleetOptions(
  partial: Partial<FleetOptions>,
  base: FleetOptions = DEFAULT_FLEET_OPTIONS,
): FleetOptions {
  return {
    visibleCount: clampVisibleCount(partial.visibleCount ?? base.visibleCount),
    backgroundFleet: partial.backgroundFleet ?? base.backgroundFleet,
    trails: partial.trails ?? base.trails,
    followFleet: partial.followFleet ?? base.followFleet,
    density: partial.density === undefined ? base.density : normalizeDensity(partial.density),
    themeId: isFleetThemeId(partial.themeId) ? partial.themeId : base.themeId,
  }
}

/**
 * The map of the fleet, as a function of the fleet and of the view.
 *
 * It is created once per page. Everything a reader changes - the count, the
 * palette, the trails, the camera - is applied to the scene that already
 * exists.
 */
export class FleetPresenter {
  private readonly renderer: FleetRenderer
  private readonly layer: VehicleLayer
  private readonly trails = new Map<string, LocalPoint[]>()
  private readonly positions = new Map<string, LocalPoint>()

  private options: FleetOptions
  private selection: string | null = null
  private anchor: GeoPoint | null = null
  private fleet: Vehicle[] = []
  private size: ViewportSize = { width: 0, height: 0 }
  private view: FleetView | null = null
  private split: FleetSplit = { rendered: [], background: [], omitted: 0 }
  private tracked = 0
  private districtHalfExtent = 0

  constructor(renderer: FleetRenderer, options: Partial<FleetOptions> = {}) {
    this.renderer = renderer
    this.options = normalizeFleetOptions(options)
    this.layer = new VehicleLayer({ create: (vehicle, state) => renderer.createNode(vehicle, state) })
    renderer.drawGround({ themeId: this.options.themeId, density: this.options.density, halfExtent: 0 })
  }

  /** The options in force, already clamped. */
  get currentOptions(): FleetOptions {
    return { ...this.options }
  }

  /** The camera, or `null` before a fleet has been fitted. */
  get currentView(): FleetView | null {
    return this.view ? { ...this.view } : null
  }

  /** The vehicle a reader selected, if any. */
  get selectedVehicleId(): string | null {
    return this.selection
  }

  /** What the last update drew, for the panel that reports the budget. */
  get counts(): FleetCounts {
    return {
      tracked: this.tracked,
      rendered: this.split.rendered.length,
      background: this.split.background.length,
      omitted: this.split.omitted,
    }
  }

  /**
   * Applies what a reader changed.
   *
   * @param partial the options that changed
   */
  setOptions(partial: Partial<FleetOptions>): void {
    const next = normalizeFleetOptions(partial, this.options)
    const previous = this.options
    this.options = next

    if (next.themeId !== previous.themeId || next.density !== previous.density) {
      this.renderer.drawGround({
        themeId: next.themeId,
        density: next.density,
        halfExtent: this.districtHalfExtent,
      })
    }
    if (next.trails !== previous.trails && !next.trails) {
      this.trails.clear()
    }
    this.render()
  }

  /**
   * @param vehicleId vehicle a reader selected, or `null` to clear it
   */
  setSelection(vehicleId: string | null): void {
    if (this.selection === vehicleId) {
      return
    }
    this.selection = vehicleId
    this.render()
  }

  /**
   * @param size new size of the drawing surface
   */
  setSize(size: ViewportSize): void {
    if (size.width === this.size.width && size.height === this.size.height) {
      return
    }
    this.size = size
    this.renderer.resize(size)
    if (this.view) {
      this.renderer.setView(this.view, size)
    }
    this.render()
  }

  /**
   * Draws a fleet.
   *
   * @param vehicles the fleet as the backend last reported it
   * @returns what the update did
   */
  update(vehicles: readonly Vehicle[]): FleetUpdate {
    this.fleet = [...vehicles]
    this.tracked = vehicles.length

    const points: LocalPoint[] = []
    for (const vehicle of vehicles) {
      const point = this.place(vehicle)
      if (point) {
        points.push(point)
      }
    }

    if (this.options.followFleet) {
      this.follow(points)
    }
    this.ensureDistrict(points)
    return this.render()
  }

  /** Points the camera at the whole fleet. */
  fitToFleet(): void {
    const fitted = fitView([...this.positions.values()], this.size.width, this.size.height)
    if (!fitted) {
      return
    }
    this.view = fitted
    this.renderer.setView(fitted, this.size)
    this.ensureDistrict([...this.positions.values()])
    this.render()
  }

  /**
   * Drags the map.
   *
   * @param deltaXPixels horizontal movement of the pointer
   * @param deltaYPixels vertical movement of the pointer
   */
  panBy(deltaXPixels: number, deltaYPixels: number): void {
    if (!this.view) {
      return
    }
    this.view = panView(this.view, deltaXPixels, deltaYPixels, this.size.height)
    this.renderer.setView(this.view, this.size)
    this.ensureDistrict([...this.positions.values()])
    this.render()
  }

  /**
   * Zooms the map around a point of the screen.
   *
   * @param offsetX pixels from the left edge of the map
   * @param offsetY pixels from the top edge of the map
   * @param factor multiplier of the half extents; below 1 zooms in
   */
  zoomAt(offsetX: number, offsetY: number, factor: number): void {
    if (!this.view) {
      return
    }
    const anchor = groundAt(this.view, offsetX, offsetY, this.size.width, this.size.height)
    this.view = zoomView(this.view, factor, anchor.x, anchor.y)
    this.renderer.setView(this.view, this.size)
    this.render()
  }

  /**
   * The vehicle under one pixel of the map, if any is close enough.
   *
   * Picking is done in the flat space of the map rather than against the
   * geometry of the meshes: a mini vehicle is drawn at a size that belongs to
   * the screen and not to the ground, so a distance in pixels is what a reader
   * actually aimed at.
   *
   * @param offsetX pixels from the left edge of the map
   * @param offsetY pixels from the top edge of the map
   * @param tolerancePixels how far from the pointer a vehicle still counts
   * @returns the vehicle, or `null` when the pointer hit the ground
   */
  pick(offsetX: number, offsetY: number, tolerancePixels = 18): Vehicle | null {
    if (!this.view) {
      return null
    }
    const target = groundAt(this.view, offsetX, offsetY, this.size.width, this.size.height)
    const tolerance = metersPerPixel(this.view, this.size.height) * tolerancePixels
    let best: { vehicle: Vehicle; distance: number } | null = null
    for (const vehicle of this.split.rendered) {
      const point = this.positions.get(vehicle.vehicleId)
      if (!point) {
        continue
      }
      const distance = Math.hypot(point.x - target.x, point.y - target.y)
      if (distance <= tolerance && (!best || distance < best.distance)) {
        best = { vehicle, distance }
      }
    }
    return best?.vehicle ?? null
  }

  /** Releases the map. */
  dispose(): void {
    this.layer.clear()
    this.trails.clear()
    this.positions.clear()
    this.renderer.dispose()
  }

  /**
   * Draws the fleet that is already known, with the options in force.
   *
   * This is the one path that touches the scene, so a change of an option and a
   * change of the fleet go through exactly the same code: the split is decided
   * again, and the layer turns the difference against what is already drawn
   * into creations, updates and removals.
   */
  private render(): FleetUpdate {
    this.split = splitFleet(this.fleet, this.options.visibleCount, this.selection)
    this.rememberTrails(this.split)

    const scale = this.nodeScale()
    const stats = this.layer.sync(this.split, (vehicle) => {
      const position = this.positions.get(vehicle.vehicleId) ?? { x: 0, y: 0 }
      return {
        selected: vehicle.vehicleId === this.selection,
        scale,
        trails: this.options.trails,
        x: position.x,
        y: position.y,
      }
    })

    const cloud: LocalPoint[] = []
    if (this.options.backgroundFleet) {
      for (const vehicle of this.split.background) {
        const point = this.positions.get(vehicle.vehicleId)
        if (point) {
          cloud.push(point)
        }
      }
    }
    this.renderer.drawCloud(cloud, { visible: this.options.backgroundFleet })
    this.renderer.drawTrails(this.trailList(), scale)

    return {
      ...stats,
      tracked: this.tracked,
      background: cloud.length,
      omitted: this.split.omitted,
    }
  }

  private trailList(): VehicleTrail[] {
    return [...this.trails].map(([vehicleId, points]) => ({ vehicleId, points }))
  }

  /**
   * @param vehicle the vehicle to place
   * @returns its position in local metres, or `undefined` for an unusable position
   */
  private place(vehicle: Vehicle): LocalPoint | undefined {
    if (!Number.isFinite(vehicle.latitude) || !Number.isFinite(vehicle.longitude)) {
      return undefined
    }
    if (!this.anchor) {
      // The anchor is the first vehicle the dashboard ever saw, and it never
      // moves: everything the map draws is a difference against it.
      this.anchor = { latitude: vehicle.latitude, longitude: vehicle.longitude }
    }
    const point = projectPoint(vehicle, this.anchor)
    this.positions.set(vehicle.vehicleId, point)
    return point
  }

  /**
   * Keeps the trail of every drawn vehicle, bounded in length and in number.
   *
   * A trail is kept for the vehicles that are drawn, so what the browser
   * remembers grows with the budget and not with the fleet.
   */
  private rememberTrails(split: FleetSplit): void {
    const drawn = new Set<string>()
    for (const vehicle of split.rendered) {
      drawn.add(vehicle.vehicleId)
      if (!this.options.trails) {
        continue
      }
      const point = this.positions.get(vehicle.vehicleId)
      if (!point) {
        continue
      }
      const trail = this.trails.get(vehicle.vehicleId) ?? []
      const previous = trail[trail.length - 1]
      if (previous && previous.x === point.x && previous.y === point.y) {
        continue
      }
      trail.push(point)
      while (trail.length > TRAIL_POINTS) {
        trail.shift()
      }
      this.trails.set(vehicle.vehicleId, trail)
    }

    for (const vehicleId of [...this.trails.keys()]) {
      if (!drawn.has(vehicleId)) {
        this.trails.delete(vehicleId)
      }
    }
  }

  /** Keeps the camera on the fleet while the option is on, without fighting a drag. */
  private follow(points: readonly LocalPoint[]): void {
    if (points.length === 0) {
      return
    }
    if (!this.view) {
      this.view = fitView(points, this.size.width, this.size.height)
      if (this.view) {
        this.renderer.setView(this.view, this.size)
      }
      return
    }
    const bounds = boundsOf(points)
    if (!bounds) {
      return
    }
    const outside =
      bounds.minX < this.view.centerX - this.view.halfWidth ||
      bounds.maxX > this.view.centerX + this.view.halfWidth ||
      bounds.minY < this.view.centerY - this.view.halfHeight ||
      bounds.maxY > this.view.centerY + this.view.halfHeight
    if (!outside) {
      return
    }
    const fitted = fitView(points, this.size.width, this.size.height)
    if (fitted) {
      this.view = fitted
      this.renderer.setView(fitted, this.size)
    }
  }

  /** Lays the district out again only when the fleet no longer fits in it. */
  private ensureDistrict(points: readonly LocalPoint[]): void {
    const bounds = boundsOf(points)
    const viewExtent = Math.max(this.view?.halfWidth ?? 0, this.view?.halfHeight ?? 0)
    const fromBounds = bounds
      ? Math.max(Math.abs(bounds.minX), Math.abs(bounds.maxX), Math.abs(bounds.minY), Math.abs(bounds.maxY))
      : 0
    const required = Math.max(fromBounds, viewExtent) * DISTRICT_MARGIN
    if (networkCoversExtent(this.districtHalfExtent, required)) {
      return
    }
    this.districtHalfExtent = Math.max(required, 1)
    this.renderer.drawGround({
      themeId: this.options.themeId,
      density: this.options.density,
      halfExtent: this.districtHalfExtent,
    })
  }

  /**
   * @returns how big one mini vehicle is in world metres, so that it keeps its
   *          size on screen however far the camera is
   */
  private nodeScale(): number {
    if (!this.view) {
      return 1
    }
    return metersPerPixel(this.view, this.size.height)
  }
}
