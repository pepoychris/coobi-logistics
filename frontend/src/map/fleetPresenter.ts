/**
 * The map, without three.js in the room.
 *
 * Everything that decides what the fleet map shows lives here - which vehicles
 * are drawn in full and which are only acknowledged, which road each of them is
 * standing on, where the camera is, which trails exist, what a click selects,
 * and when the procedural district has to be laid out again. The renderer is an
 * interface with one implementation that talks to the GPU and one stand-in that
 * records calls, so the rules of the map are tested without a browser and the
 * drawing code stays a thin translation of them.
 *
 * Three of those rules are worth naming, because they are what keeps the map
 * usable while a fleet is large and believable while it is small:
 *
 * - the fleet is split into a drawn half and a faint cloud by {@link splitFleet},
 *   so the number of mini vehicles never exceeds {@link MAX_RENDERED_VEHICLES},
 *   however many vehicles report;
 * - every vehicle is seated on the road graph of the district by
 *   {@link planTraffic} before it is drawn, so a vehicle is only ever seen on a
 *   street and never on a block or a roof;
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
import {
  buildRoadNetwork,
  DISTRICT_SEED,
  MIN_DISTRICT_HALF_EXTENT,
  narrowestRoadWidth,
  networkCoversExtent,
  normalizeDensity,
  type NetworkDensity,
  type RoadNetwork,
} from './roadNetwork'
import { DEFAULT_THEME_ID, isFleetThemeId, type FleetThemeId } from './theme'
import { planTraffic, type TrafficEntry } from './traffic'
import { VehicleLayer, type VehicleLayerStats, type VehicleNode, type VehicleNodeState } from './vehicleLayer'
import { VEHICLE_LENGTH_METERS, VEHICLE_MARKER_PIXELS } from './vehicleMesh'

/** Size of the drawing surface, in CSS pixels. */
export interface ViewportSize {
  width: number
  height: number
}

/** How many positions of a vehicle the trail keeps. */
export const TRAIL_POINTS = 14

/** How much of the view the district leaves around the fleet, as a factor. */
export const DISTRICT_MARGIN = 1.15

/**
 * How much smaller than the fleet the district may become before it is laid out
 * again.
 *
 * The district follows the ground the reader is looking at: it grows when the
 * fleet outgrows it, and it is redrawn smaller when the camera has come back so
 * far in that the reader would be looking at a handful of enormous blocks.
 */
export const DISTRICT_SHRINK_FACTOR = 0.4

/**
 * Largest part of a road a mini vehicle may cover, as a fraction of its width.
 *
 * A mini vehicle is drawn at a size of its own so that a reader can see it
 * however far the camera is, which is what makes a fleet of hundreds readable -
 * but a van drawn wider than the street it is driving down would make the city
 * look like a toy. So the drawn vehicle is capped at a fraction of the narrowest
 * road of the district, and shrinks with the camera once the camera is far
 * enough out for the cap to bite.
 */
export const MAX_VEHICLE_ROAD_SHARE = 0.4

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
  /**
   * The district itself.
   *
   * The presenter lays it out and the renderer draws what it is given, so the
   * city a reader sees and the streets the fleet is placed on are the same
   * object rather than two layouts that have to be kept in step.
   */
  network: RoadNetwork
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
  /**
   * Replaces the faint cloud with the vehicles that are outside the budget.
   *
   * @param points where those vehicles are, in local metres
   * @param options whether the cloud is drawn, and how big one of its dots is on screen
   */
  drawCloud(points: readonly LocalPoint[], options: { visible: boolean; sizePixels: number }): void
  /** Replaces the trails of the fleet. `scale` is the world length of one drawn vehicle. */
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
  /** The fleet as the backend last reported it, by identifier. */
  private readonly reported = new Map<string, Vehicle>()
  /** Where the fleet reported itself, in the local metres of the map. */
  private readonly points = new Map<string, LocalPoint>()
  /** Where the fleet is drawn, which is always on a road of the district. */
  private readonly positions = new Map<string, LocalPoint>()
  /** Which way the vehicle of each position is pointing, along its lane. */
  private readonly headings = new Map<string, number>()

  private options: FleetOptions
  private selection: string | null = null
  private anchor: GeoPoint | null = null
  private fleet: Vehicle[] = []
  private size: ViewportSize = { width: 0, height: 0 }
  private view: FleetView | null = null
  private split: FleetSplit = { rendered: [], background: [], omitted: 0 }
  private tracked = 0
  private district: RoadNetwork

  constructor(renderer: FleetRenderer, options: Partial<FleetOptions> = {}) {
    this.renderer = renderer
    this.options = normalizeFleetOptions(options)
    this.layer = new VehicleLayer({ create: (vehicle, state) => renderer.createNode(vehicle, state) })
    this.district = this.layOutDistrict(MIN_DISTRICT_HALF_EXTENT)
    renderer.drawGround({ themeId: this.options.themeId, network: this.district })
  }

  /** The options in force, already clamped. */
  get currentOptions(): FleetOptions {
    return { ...this.options }
  }

  /** The camera, or `null` before a fleet has been fitted. */
  get currentView(): FleetView | null {
    return this.view ? { ...this.view } : null
  }

  /** The district the fleet is driving through. */
  get currentDistrict(): RoadNetwork {
    return this.district
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

    const repainted = next.themeId !== previous.themeId
    const relaid = next.density !== previous.density
    if (relaid) {
      this.district = this.layOutDistrict(this.district.halfExtent)
    }
    if (repainted || relaid) {
      this.renderer.drawGround({ themeId: next.themeId, network: this.district })
    }
    if (relaid) {
      // New streets: the fleet is seated on them again before it is drawn.
      this.plan()
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

    this.project()
    this.ensureDistrict()
    this.plan()
    if (this.options.followFleet) {
      this.follow()
    }
    return this.render()
  }

  /** Points the camera at the whole fleet. */
  fitToFleet(): void {
    const fitted = fitView([...this.points.values()], this.size.width, this.size.height)
    if (!fitted) {
      return
    }
    this.view = fitted
    this.renderer.setView(fitted, this.size)
    if (this.ensureDistrict()) {
      this.plan()
    }
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
    if (this.ensureDistrict()) {
      this.plan()
    }
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
    if (this.ensureDistrict()) {
      this.plan()
    }
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
    const tolerance = Math.max(
      metersPerPixel(this.view, this.size.height) * tolerancePixels,
      this.markerWorldLength(),
    )
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
    this.points.clear()
    this.positions.clear()
    this.headings.clear()
    this.reported.clear()
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

    const scale = this.markerWorldLength()
    const stats = this.layer.sync(this.split, (vehicle) => {
      const position = this.positions.get(vehicle.vehicleId) ?? { x: 0, y: 0 }
      return {
        selected: vehicle.vehicleId === this.selection,
        scale,
        trails: this.options.trails,
        heading: this.headings.get(vehicle.vehicleId) ?? vehicle.heading,
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
    this.renderer.drawCloud(cloud, {
      visible: this.options.backgroundFleet,
      sizePixels: this.cloudDotPixels(),
    })
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
   * Turns the fleet the backend reported into positions in the local metres of
   * the map, and remembers the vehicle each of them belongs to.
   */
  private project(): void {
    this.points.clear()
    this.reported.clear()
    for (const vehicle of this.fleet) {
      this.reported.set(vehicle.vehicleId, vehicle)
      if (!Number.isFinite(vehicle.latitude) || !Number.isFinite(vehicle.longitude)) {
        continue
      }
      if (!this.anchor) {
        // The anchor is the first vehicle the dashboard ever saw, and it never
        // moves: everything the map draws is a difference against it.
        this.anchor = { latitude: vehicle.latitude, longitude: vehicle.longitude }
      }
      this.points.set(vehicle.vehicleId, projectPoint(vehicle, this.anchor))
    }
  }

  /**
   * Seats the fleet on the roads of the district.
   *
   * This is the step that keeps a vehicle off the buildings: what the backend
   * reports is a position, and what the map draws is the nearest legal piece of
   * road to it - shared with nothing else, unless the district has more vehicles
   * than it has road.
   */
  private plan(): void {
    this.positions.clear()
    this.headings.clear()
    const entries: TrafficEntry[] = []
    for (const [vehicleId, point] of this.points) {
      entries.push({
        vehicleId,
        x: point.x,
        y: point.y,
        heading: this.reported.get(vehicleId)?.heading,
      })
    }
    for (const [vehicleId, placement] of planTraffic(this.district, entries)) {
      this.positions.set(vehicleId, { x: placement.x, y: placement.y })
      this.headings.set(vehicleId, placement.heading)
    }
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
  private follow(): void {
    const points = [...this.points.values()]
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

  /**
   * Lays the district out again only when the fleet no longer fits in it, or
   * when the camera has come back in so far that the reader would be looking at
   * a handful of enormous blocks.
   *
   * @returns whether the district was laid out again, which means the fleet has
   *          to be seated on it before it is drawn
   */
  private ensureDistrict(): boolean {
    const required = this.requiredHalfExtent()
    const covered = networkCoversExtent(this.district.halfExtent, required)
    if (covered && required >= this.district.halfExtent * DISTRICT_SHRINK_FACTOR) {
      return false
    }
    this.district = this.layOutDistrict(Math.max(required, MIN_DISTRICT_HALF_EXTENT))
    this.renderer.drawGround({ themeId: this.options.themeId, network: this.district })
    return true
  }

  /**
   * @returns the half width of the ground the map needs right now: the fleet,
   *          and the piece of the world the camera is looking at, with room
   *          around both
   */
  private requiredHalfExtent(): number {
    const bounds = boundsOf([...this.points.values()])
    const viewExtent = Math.max(this.view?.halfWidth ?? 0, this.view?.halfHeight ?? 0)
    const fromBounds = bounds
      ? Math.max(Math.abs(bounds.minX), Math.abs(bounds.maxX), Math.abs(bounds.minY), Math.abs(bounds.maxY))
      : 0
    return Math.max(fromBounds, viewExtent) * DISTRICT_MARGIN
  }

  /**
   * @param halfExtent half width of the district to lay out
   * @returns the district of that size, with the density the reader selected
   */
  private layOutDistrict(halfExtent: number): RoadNetwork {
    return buildRoadNetwork({
      seed: DISTRICT_SEED,
      halfExtent,
      density: this.options.density,
    })
  }

  /**
   * @returns the world length of one drawn mini vehicle
   *
   * A mini vehicle is normally drawn at the same size on screen whatever the
   * camera does, which is what keeps a fleet of hundreds readable. The cap is
   * the one thing that wins over it: a vehicle is never drawn longer than a
   * fraction of the narrowest street of the district, so the city never looks
   * like it is made of vans.
   */
  private markerWorldLength(): number {
    if (!this.view) {
      return VEHICLE_LENGTH_METERS
    }
    const perPixel = metersPerPixel(this.view, this.size.height)
    const wanted = perPixel * VEHICLE_MARKER_PIXELS
    const cap = narrowestRoadWidth(this.district) * MAX_VEHICLE_ROAD_SHARE
    return Math.max(VEHICLE_LENGTH_METERS, Math.min(wanted, cap))
  }

  /**
   * @returns how big one dot of the faint cloud is on screen, in CSS pixels
   *
   * The cloud is drawn at the size of a mini vehicle, so that the vehicles the
   * budget draws and the ones it merely acknowledges read as the same fleet.
   */
  private cloudDotPixels(): number {
    if (!this.view) {
      return VEHICLE_MARKER_PIXELS * 0.55
    }
    const perPixel = metersPerPixel(this.view, this.size.height)
    return Math.max(1.5, this.markerWorldLength() / perPixel)
  }
}
