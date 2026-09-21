/**
 * The one implementation of {@link FleetRenderer}: three.js, an orthographic
 * camera looking straight down, and a scene that is drawn again only when
 * something changed.
 *
 * The scene holds five things, and none of them is created per frame:
 *
 * - the ground, merged into one mesh per kind of patch and one per kind of road;
 * - the faint cloud, one buffer of points for every vehicle outside the budget;
 * - the trails, one buffer of line segments for the drawn vehicles;
 * - the mini vehicles, one mesh each and never more than the budget allows;
 * - the selection ring, a single mesh that moves to the selected vehicle.
 *
 * A tick therefore costs the vehicles that moved and never the size of the
 * fleet: everything outside the budget arrives as one buffer of points, which
 * the GPU draws in a single call.
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
import { buildRoadNetwork, networkCoversExtent, type DistrictKind, type Road, type RoadNetwork } from './roadNetwork'
import { fleetTheme, type FleetTheme } from './theme'
import {
  createVehicleGeometryLibrary,
  VEHICLE_LENGTH_METERS,
  VEHICLE_MARKER_PIXELS,
  type VehicleGeometryLibrary,
} from './vehicleMesh'
import type { VehicleNode, VehicleNodeState } from './vehicleLayer'

/** Depth of the layers of the map; higher is nearer the camera. */
const LAYER_Z = {
  ground: -0.6,
  cloud: -0.35,
  trail: -0.2,
  vehicle: 0,
  selection: 0.2,
} as const

/** How many points the buffer of the faint cloud can hold. */
const CLOUD_CAPACITY = MAX_BACKGROUND_VEHICLES

/** How many trail segments the buffer can hold. */
const TRAIL_SEGMENT_CAPACITY = MAX_RENDERED_VEHICLES * (TRAIL_POINTS - 1)

/** Width of a lane marking, as a fraction of the road it belongs to. */
const MARKING_WIDTH = 0.09

/** Length of one dash of a lane marking, as a multiple of the road width. */
const DASH_LENGTH = 1.6

/** Where the district is drawn when nothing has been reported yet. */
const DEFAULT_DISTRICT_HALF_EXTENT = 2_400

/** A fixed seed, so every dashboard draws the same city. */
const DISTRICT_SEED = 20260101

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
  let network: RoadNetwork | null = null
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
    size: VEHICLE_MARKER_PIXELS * 0.55,
    sizeAttenuation: false,
    transparent: true,
    opacity: 0.3,
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
  let selectionGeometry = paintGeometry(new RingGeometry(0.66, 0.86, 28), String(activeTheme.selection))
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

  /** The world size of one mini vehicle, from the size the presenter asked for. */
  function meshScale(state: VehicleNodeState): number {
    return (state.scale * VEHICLE_MARKER_PIXELS) / VEHICLE_LENGTH_METERS
  }

  function placeSelection(): void {
    if (!selectionHost) {
      return
    }
    selection.position.set(selectionHost.position.x, selectionHost.position.y, LAYER_Z.selection)
  }

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
        mesh.scale.setScalar(meshScale(view))
        mesh.rotation.z = -(next.heading * Math.PI) / 180
        if (view.selected) {
          selectionHost = mesh
          selection.visible = true
          selection.scale.setScalar(meshScale(view))
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

  function drawCloud(points: readonly LocalPoint[], options: { visible: boolean }): void {
    const attribute = cloudGeometry.getAttribute('position') as Float32BufferAttribute
    const count = Math.min(points.length, CLOUD_CAPACITY)
    for (let index = 0; index < count; index += 1) {
      const point = points[index]
      attribute.setXYZ(index, point.x, point.y, LAYER_Z.cloud)
    }
    attribute.needsUpdate = true
    cloudGeometry.setDrawRange(0, count)
    cloud.visible = options.visible && count > 0
    requestFrame()
  }

  function drawTrails(list: readonly VehicleTrail[], scale: number): void {
    const attribute = trailGeometry.getAttribute('position') as Float32BufferAttribute
    // A segment shorter than a tenth of the drawn vehicle is a rounding of the
    // same position rather than a movement, and is left out.
    const minimum = scale * VEHICLE_MARKER_PIXELS * 0.1
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
    selectionGeometry = paintGeometry(new RingGeometry(0.66, 0.86, 28), String(theme.selection))
    selection.geometry = selectionGeometry
  }

  function drawGround(look: GroundLook): void {
    applyTheme(fleetTheme(look.themeId))

    const requested = look.halfExtent > 0 ? look.halfExtent : DEFAULT_DISTRICT_HALF_EXTENT
    if (!network || !networkCoversExtent(network.halfExtent, requested) || requested < network.halfExtent * 0.4) {
      network = buildRoadNetwork({ seed: DISTRICT_SEED, halfExtent: requested, density: look.density })
    }

    for (const child of [...ground.children]) {
      ground.remove(child)
      if (child instanceof Mesh) {
        child.geometry.dispose()
      }
    }
    for (const material of groundMaterials.splice(0)) {
      material.dispose()
    }

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
          z: LAYER_Z.ground + (kind === 'depot' ? 0.02 : 0),
          color: String(activeTheme.blockEdge),
        }),
        paintedArea({
          x: patch.x + kerb,
          y: patch.y + kerb,
          width: Math.max(patch.width - kerb * 2, 1),
          height: Math.max(patch.height - kerb * 2, 1),
          z: LAYER_Z.ground + (kind === 'depot' ? 0.03 : 0.01),
          color: groundColor(activeTheme, kind),
        }),
      ])
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(parts, material, `district-${kind}`))
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
          z: LAYER_Z.ground + (kind === 'street' ? 0.05 : 0.08),
          color: String(activeTheme.road),
        }),
      )
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(parts, material, `road-${kind}`))
    }

    const markings = network.roads.flatMap((road) => dashMarkings(road, String(activeTheme.marking)))
    if (markings.length > 0) {
      const material = flatMaterial()
      groundMaterials.push(material)
      ground.add(mergedMesh(markings, material, 'road-markings'))
    }
    requestFrame()
  }

  function dispose(): void {
    disposed = true
    if (frame !== null) {
      globalThis.cancelAnimationFrame(frame)
      frame = null
    }
    for (const child of [...ground.children]) {
      ground.remove(child)
      if (child instanceof Mesh) {
        child.geometry.dispose()
      }
    }
    for (const material of groundMaterials.splice(0)) {
      material.dispose()
    }
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
 * The dashes of a lane marking along one road.
 *
 * @param road the road to mark
 * @param color colour of the marking
 * @returns one small rectangle per dash, ready to be merged
 */
function dashMarkings(road: Road, color: string): BufferGeometry[] {
  const length = Math.hypot(road.x2 - road.x1, road.y2 - road.y1)
  const angle = Math.atan2(road.y2 - road.y1, road.x2 - road.x1)
  const dash = Math.max(road.width * DASH_LENGTH, 6)
  const stride = dash * 2.6
  const count = Math.floor(length / stride)
  const parts: BufferGeometry[] = []
  for (let index = 0; index < count; index += 1) {
    const travelled = index * stride + stride / 2
    parts.push(
      paintedRectangle({
        centerX: road.x1 + Math.cos(angle) * travelled,
        centerY: road.y1 + Math.sin(angle) * travelled,
        length: dash,
        width: road.width * MARKING_WIDTH,
        angle,
        z: LAYER_Z.ground + 0.12,
        color,
      }),
    )
  }
  return parts
}
