/**
 * The one implementation of {@link FleetRenderer}: three.js, an orthographic
 * camera looking straight down, and a scene that is drawn again only when
 * something changed.
 *
 * The scene holds five things, and none of them is created per frame:
 *
 * - the ground, merged into one mesh per kind of patch - the blocks, the
 *   buildings standing on them, the parks, the channels, the depot - and one per
 *   kind of road;
 * - the faint cloud, one buffer of points for every vehicle outside the budget;
 * - the trails, one buffer of line segments for the drawn vehicles;
 * - the mini vehicles, one mesh each and never more than the budget allows;
 * - the selection ring, a single mesh that moves to the selected vehicle.
 *
 * A tick therefore costs the vehicles that moved and never the size of the
 * fleet: everything outside the budget arrives as one buffer of points, which
 * the GPU draws in a single call.
 *
 * The district itself is not built here. The presenter lays it out, seats the
 * fleet on the roads it contains and hands the same object to
 * {@link FleetRenderer.drawGround}, so what a reader sees and what the vehicles
 * are placed on cannot drift apart.
 */

import {
  BufferGeometry,
  Color,
  Float32BufferAttribute,
  Group,
  LineBasicMaterial,
  LineSegments,
  Mesh,
  MeshBasicMaterial,
  OrthographicCamera,
  Points,
  PointsMaterial,
  RingGeometry,
  Scene,
  WebGLRenderer,
} from 'three'

import type { Vehicle } from '../api/types'
import { MAX_BACKGROUND_VEHICLES, MAX_RENDERED_VEHICLES } from './budget'
import type { FleetRenderer, GroundLook, VehicleTrail, ViewportSize } from './fleetPresenter'
import { TRAIL_POINTS } from './fleetPresenter'
import { flatMaterial, mergedMesh, paintGeometry, paintedArea, paintedRectangle, paintedSegment } from './geometryParts'
import type { FleetView, LocalPoint } from './projection'
import type { District, DistrictKind, Road, RoadNetwork } from './roadNetwork'
import { laneWidth, lanesInDirection } from './roadNetwork'
import { fleetTheme, type FleetTheme } from './theme'
import {
  createVehicleGeometryLibrary,
  meshRotationRadians,
  VEHICLE_LENGTH_METERS,
  VEHICLE_MARKER_PIXELS,
  type VehicleGeometryLibrary,
} from './vehicleMesh'
import type { VehicleNode, VehicleNodeState } from './vehicleLayer'

/** Depth of the layers of the map; higher is nearer the camera. */
const LAYER_Z = {
  plotEdge: -0.72,
  plot: -0.7,
  buildingShadow: -0.66,
  building: -0.64,
  road: -0.55,
  marking: -0.5,
  cloud: -0.35,
  trail: -0.2,
  vehicle: 0,
  selection: 0.2,
} as const

/** How many points the buffer of the faint cloud can hold. */
const CLOUD_CAPACITY = MAX_BACKGROUND_VEHICLES

/** How many trail segments the buffer can hold. */
const TRAIL_SEGMENT_CAPACITY = MAX_RENDERED_VEHICLES * (TRAIL_POINTS - 1)

/** Width of a lane marking, as a fraction of the lane it belongs to. */
const MARKING_WIDTH = 0.06

/** Length of one dash of a lane marking, as a multiple of the width of the road. */
const DASH_LENGTH = 1.6

/** Size of the mini vehicle when nothing has reported yet, in CSS pixels. */
const FALLBACK_MARKER_PIXELS = VEHICLE_MARKER_PIXELS

/**
 * How far a building leans on the ground, as a fraction of its own footprint,
 * once per floor it carries.
 *
 * The map looks straight down, so a building has no height to show. The offset
 * shadow is what gives it one: it is drawn a little to the south-west of the
 * roof, further the taller the block is, which is what turns a rectangle on a
 * plot into a block of flats.
 */
const SHADOW_LEAN = 0.045
const SHADOW_LEAN_PER_LEVEL = 0.02

/** Share of a depot plot given to the painted parking bays of the yard. */
const DEPOT_BAY_SHARE = 0.05

/**
 * @param container element the canvas is added to
 * @param theme palette to open with
 * @returns a renderer that draws a fleet, or throws when the browser has no WebGL
 */
export function createWebglFleetRenderer(container: HTMLElement, theme: FleetTheme): FleetRenderer {
  const renderer = new WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' })
  renderer.setPixelRatio(Math.min(globalThis.devicePixelRatio || 1, 2))
  renderer.domElement.classList.add('fleet-map__canvas')
  container.append(renderer.domElement)

  const scene = new Scene()
  const camera = new OrthographicCamera(-1, 1, 1, -1, 0.1, 1_000)
  camera.position.set(0, 0, 20)

  const ground = new Group()
  ground.name = 'district'
  const faint = new Group()
  const fleet = new Group()
  scene.add(ground, faint, fleet)

  let activeTheme = theme
  let library = createVehicleGeometryLibrary(activeTheme)
  let size: ViewportSize = { width: 1, height: 1 }
  let frame: number | null = null
  let disposed = false
  let selectionHost: Mesh | null = null
  const groundMaterials: MeshBasicMaterial[] = []
  const restylers = new Set<(next: VehicleGeometryLibrary) => void>()

  const cloudGeometry = new BufferGeometry()
  cloudGeometry.setAttribute('position', new Float32BufferAttribute(new Float32Array(CLOUD_CAPACITY * 3), 3))
  cloudGeometry.setDrawRange(0, 0)
  const cloudMaterial = new PointsMaterial({
    color: new Color(String(activeTheme.backgroundFleet)),
    size: FALLBACK_MARKER_PIXELS * 0.55,
    sizeAttenuation: false,
    transparent: true,
    opacity: 0.32,
    depthWrite: false,
    toneMapped: false,
  })
  const cloud = new Points(cloudGeometry, cloudMaterial)
  cloud.name = 'fleet-faint'
  cloud.frustumCulled = false
  faint.add(cloud)

  const trailGeometry = new BufferGeometry()
  trailGeometry.setAttribute('position', new Float32BufferAttribute(new Float32Array(TRAIL_SEGMENT_CAPACITY * 6), 3))
  trailGeometry.setDrawRange(0, 0)
  const trailMaterial = new LineBasicMaterial({
    color: new Color(String(activeTheme.trail)),
    transparent: true,
    opacity: 0.55,
    depthWrite: false,
    toneMapped: false,
  })
  const trails = new LineSegments(trailGeometry, trailMaterial)
  trails.name = 'fleet-trails'
  trails.frustumCulled = false
  faint.add(trails)

  const selectionMaterial = flatMaterial()
  let selectionGeometry = ringGeometry(String(activeTheme.selection))
  const selection = new Mesh(selectionGeometry, selectionMaterial)
  selection.name = 'fleet-selection'
  selection.frustumCulled = false
  selection.visible = false
  fleet.add(selection)

  scene.background = new Color(String(activeTheme.background))

  function requestFrame(): void {
    if (disposed || frame !== null) {
      return
    }
    frame = globalThis.requestAnimationFrame(() => {
      frame = null
      renderer.render(scene, camera)
    })
  }

  function applyViewport(): void {
    renderer.setSize(size.width, size.height, false)
    camera.updateProjectionMatrix()
    requestFrame()
  }

  function placeSelection(): void {
    if (!selectionHost) {
      return
    }
    selection.position.set(selectionHost.position.x, selectionHost.position.y, LAYER_Z.selection)
  }

  /**
   * The transform of one mini vehicle.
   *
   * The state carries the length the vehicle is drawn at, in world metres, and
   * the heading of the lane it is driving in; the geometry it draws is the
   * shape of a van in metres, facing east in its own space, which is what the
   * rotation turns towards the heading.
   */
  function createNode(vehicle: Vehicle, state: VehicleNodeState): VehicleNode {
    const mesh = new Mesh(library.geometryFor(vehicle.status), library.material)
    mesh.name = vehicle.vehicleId
    mesh.frustumCulled = false
    fleet.add(mesh)

    let status = vehicle.status
    const restyle = (next: VehicleGeometryLibrary): void => {
      mesh.geometry = next.geometryFor(status)
      mesh.material = next.material
    }
    restylers.add(restyle)

    const node: VehicleNode = {
      update: (next, view) => {
        status = next.status
        mesh.geometry = library.geometryFor(status)
        mesh.position.set(view.x, view.y, LAYER_Z.vehicle)
        mesh.scale.setScalar(view.scale / VEHICLE_LENGTH_METERS)
        mesh.rotation.z = meshRotationRadians(view.heading)
        if (view.selected) {
          selectionHost = mesh
          selection.visible = true
          selection.scale.setScalar(view.scale / VEHICLE_LENGTH_METERS)
          placeSelection()
        } else if (selectionHost === mesh) {
          selectionHost = null
          selection.visible = false
        }
        requestFrame()
      },
      remove: () => {
        if (selectionHost === mesh) {
          selectionHost = null
          selection.visible = false
        }
        fleet.remove(mesh)
        restylers.delete(restyle)
        requestFrame()
      },
    }
    node.update(vehicle, state)
    return node
  }

  function drawCloud(points: readonly LocalPoint[], options: { visible: boolean; sizePixels: number }): void {
    const attribute = cloudGeometry.getAttribute('position') as Float32BufferAttribute
    const count = Math.min(points.length, CLOUD_CAPACITY)
    for (let index = 0; index < count; index += 1) {
      const point = points[index]
      attribute.setXYZ(index, point.x, point.y, LAYER_Z.cloud)
    }
    attribute.needsUpdate = true
    cloudGeometry.setDrawRange(0, count)
    cloudMaterial.size = Math.max(1.5, options.sizePixels)
    cloud.visible = options.visible && count > 0
    requestFrame()
  }

  function drawTrails(list: readonly VehicleTrail[], scale: number): void {
    const attribute = trailGeometry.getAttribute('position') as Float32BufferAttribute
    // A segment shorter than a tenth of the drawn vehicle is a rounding of the
    // same position rather than a movement, and is left out.
    const minimum = scale * 0.1
    let segment = 0
    for (const trail of list) {
      for (let index = 1; index < trail.points.length && segment < TRAIL_SEGMENT_CAPACITY; index += 1) {
        const from = trail.points[index - 1]
        const to = trail.points[index]
        if (Math.hypot(to.x - from.x, to.y - from.y) < minimum) {
          continue
        }
        attribute.setXYZ(segment * 2, from.x, from.y, LAYER_Z.trail)
        attribute.setXYZ(segment * 2 + 1, to.x, to.y, LAYER_Z.trail)
        segment += 1
      }
    }
    attribute.needsUpdate = true
    trailGeometry.setDrawRange(0, segment * 2)
    trails.visible = segment > 0
    requestFrame()
  }

  function setView(view: FleetView, next: ViewportSize): void {
    size = next
    camera.left = view.centerX - view.halfWidth
    camera.right = view.centerX + view.halfWidth
    camera.top = view.centerY + view.halfHeight
    camera.bottom = view.centerY - view.halfHeight
    camera.position.set(view.centerX, view.centerY, 20)
    applyViewport()
    placeSelection()
  }

  function resize(next: ViewportSize): void {
    size = next
    applyViewport()
  }

  /** Repaints everything of the scene that carries a colour of the palette. */
  function applyTheme(theme: FleetTheme): void {
    const changed = theme.id !== activeTheme.id
    activeTheme = theme
    scene.background = new Color(String(theme.background))
    cloudMaterial.color = new Color(String(theme.backgroundFleet))
    trailMaterial.color = new Color(String(theme.trail))
    if (!changed) {
      return
    }

    const previous = library
    library = createVehicleGeometryLibrary(theme)
    for (const restyle of restylers) {
      restyle(library)
    }
    previous.dispose()

    selectionGeometry.dispose()
    selectionGeometry = ringGeometry(String(theme.selection))
    selection.geometry = selectionGeometry
  }

  /** Releases every mesh of the ground, so a district can be drawn again. */
  function clearGround(): void {
    for (const child of [...ground.children]) {
      ground.remove(child)
      if (child instanceof Mesh) {
        child.geometry.dispose()
      }
    }
    for (const material of groundMaterials.splice(0)) {
      material.dispose()
    }
  }

  function drawGround(look: GroundLook): void {
    applyTheme(fleetTheme(look.themeId))
    clearGround()
    paintDistrict(look.network)
    requestFrame()
  }

  /**
   * Paints the district: the plots of the blocks, the buildings standing on
   * them, the parks, the channels, the depot, the roads and their markings.
   *
   * Every kind of patch and every kind of road is merged into one mesh, so a
   * district of a hundred blocks and a thousand buildings costs a handful of
   * draw calls instead of a handful of thousands.
   */
  function paintDistrict(network: RoadNetwork): void {
    const patchKinds: DistrictKind[] = ['block', 'park', 'water', 'depot']
    for (const kind of patchKinds) {
      const patches = network.districts.filter((district) => district.kind === kind)
      if (patches.length === 0) {
        continue
      }
      // Every patch is drawn twice - its kerb in the edge colour and its
      // surface a little inside it - which is what makes a district of blocks
      // read as a city instead of as one flat rectangle.
      const kerb = network.spacing * 0.022
      const parts = patches.flatMap((patch) => [
        paintedArea({
          x: patch.x,
          y: patch.y,
          width: patch.width,
          height: patch.height,
          z: LAYER_Z.plotEdge,
          color: String(activeTheme.blockEdge),
        }),
        paintedArea({
          x: patch.x + kerb,
          y: patch.y + kerb,
          width: Math.max(patch.width - kerb * 2, 1),
          height: Math.max(patch.height - kerb * 2, 1),
          z: LAYER_Z.plot,
          color: groundColor(activeTheme, kind),
        }),
      ])
      if (kind === 'depot') {
        parts.push(...patches.flatMap((patch) => depotBays(patch, network.spacing, String(activeTheme.marking))))
      }
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(parts, material, `district-${kind}`))
    }

    const shadows: BufferGeometry[] = []
    const roofs: BufferGeometry[] = []
    for (const building of network.buildings) {
      const lean =
        Math.min(building.width, building.height) * (SHADOW_LEAN + SHADOW_LEAN_PER_LEVEL * building.levels)
      shadows.push(
        paintedArea({
          x: building.x + lean * 0.6,
          y: building.y - lean,
          width: building.width,
          height: building.height,
          z: LAYER_Z.buildingShadow,
          color: String(activeTheme.buildingShadow),
        }),
      )
      roofs.push(
        paintedArea({
          x: building.x,
          y: building.y,
          width: building.width,
          height: building.height,
          z: LAYER_Z.building,
          color: String(activeTheme.building),
        }),
      )
    }
    if (shadows.length > 0) {
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(shadows, material, 'buildings-shadow'))
    }
    if (roofs.length > 0) {
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(roofs, material, 'buildings'))
    }

    const roadKinds: Road['kind'][] = ['street', 'avenue', 'ring']
    for (const kind of roadKinds) {
      const roads = network.roads.filter((road) => road.kind === kind)
      if (roads.length === 0) {
        continue
      }
      const parts = roads.map((road) =>
        paintedSegment({
          x1: road.x1,
          y1: road.y1,
          x2: road.x2,
          y2: road.y2,
          width: road.width,
          z: LAYER_Z.road,
          color: String(activeTheme.road),
        }),
      )
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(parts, material, `road-${kind}`))
    }

    const markings = network.roads.flatMap((road) => laneMarkings(road, String(activeTheme.marking)))
    if (markings.length > 0) {
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(markings, material, 'road-markings'))
    }
  }

  function dispose(): void {
    disposed = true
    if (frame !== null) {
      globalThis.cancelAnimationFrame(frame)
      frame = null
    }
    clearGround()
    restylers.clear()
    cloudGeometry.dispose()
    cloudMaterial.dispose()
    trailGeometry.dispose()
    trailMaterial.dispose()
    selectionGeometry.dispose()
    selectionMaterial.dispose()
    library.dispose()
    renderer.dispose()
    renderer.domElement.remove()
  }

  return { createNode, drawCloud, drawTrails, setView, drawGround, resize, dispose }
}

/** The colour of one kind of ground in a palette. */
function groundColor(theme: FleetTheme, kind: DistrictKind): string {
  switch (kind) {
    case 'park':
      return String(theme.park)
    case 'water':
      return String(theme.water)
    case 'depot':
      return String(theme.depot)
    default:
      return String(theme.block)
  }
}

/**
 * The ring drawn around the vehicle a reader selected: a little larger than a
 * mini vehicle, so it frames it instead of hiding it.
 */
function ringGeometry(color: string): BufferGeometry {
  return paintGeometry(new RingGeometry(4.1, 4.7, 32), color)
}

/**
 * The painted parking bays of the depot yard.
 *
 * The depot is one plot of the district and the one place where nothing drives,
 * so it is drawn as what it is: a yard of short bays where the vans stand.
 *
 * @param plot ground of the depot
 * @param spacing distance between two lines of the grid
 * @param color colour of the markings
 * @returns one small rectangle per bay, ready to be merged
 */
function depotBays(plot: District, spacing: number, color: string): BufferGeometry[] {
  const bay = Math.max(spacing * DEPOT_BAY_SHARE, 2)
  const pitch = bay * 1.6
  const columns = Math.max(1, Math.floor(plot.width / pitch))
  const rows = Math.max(1, Math.floor(plot.height / pitch))
  const parts: BufferGeometry[] = []
  for (let row = 0; row < rows; row += 1) {
    for (let column = 0; column < columns; column += 1) {
      parts.push(
        paintedArea({
          x: plot.x + column * pitch + bay * 0.3,
          y: plot.y + row * pitch + bay * 0.2,
          width: bay,
          height: bay * 0.62,
          z: LAYER_Z.plot + 0.02,
          color,
        }),
      )
    }
  }
  return parts
}

/**
 * The markings of one road: a dashed centre line between the two directions and
 * a solid line between every pair of lanes on the same side.
 *
 * @param road the road to mark
 * @param color colour of the markings
 * @returns one small rectangle per dash or line, ready to be merged
 */
function laneMarkings(road: Road, color: string): BufferGeometry[] {
  const length = Math.hypot(road.x2 - road.x1, road.y2 - road.y1)
  if (length === 0) {
    return []
  }
  const angle = Math.atan2(road.y2 - road.y1, road.x2 - road.x1)
  const step = laneWidth(road)
  const perSide = lanesInDirection(road)
  const boundaries: { offset: number; dashed: boolean }[] = [{ offset: 0, dashed: true }]
  for (let lane = 1; lane < perSide; lane += 1) {
    boundaries.push({ offset: lane * step, dashed: false })
    boundaries.push({ offset: -lane * step, dashed: false })
  }

  const parts: BufferGeometry[] = []
  const dash = Math.max(road.width * DASH_LENGTH, 6)
  for (const boundary of boundaries) {
    if (!boundary.dashed) {
      parts.push(
        paintedRectangle({
          centerX: road.x1 + (road.x2 - road.x1) / 2 - Math.sin(angle) * boundary.offset,
          centerY: road.y1 + (road.y2 - road.y1) / 2 + Math.cos(angle) * boundary.offset,
          length: length * 0.94,
          width: laneWidth(road) * MARKING_WIDTH,
          angle,
          z: LAYER_Z.marking,
          color,
        }),
      )
      continue
    }
    const stride = dash * 2.6
    const count = Math.floor(length / stride)
    for (let index = 0; index < count; index += 1) {
      const travelled = index * stride + stride / 2
      parts.push(
        paintedRectangle({
          centerX: road.x1 + Math.cos(angle) * travelled,
          centerY: road.y1 + Math.sin(angle) * travelled,
          length: dash,
          width: road.width * MARKING_WIDTH,
          angle,
          z: LAYER_Z.marking,
          color,
        }),
      )
    }
  }
  return parts
}
