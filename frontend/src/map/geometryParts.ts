/**
 * The little vocabulary the procedural map is drawn with.
 *
 * Everything on this map is a flat rectangle seen from above - a block, a
 * road, a wheel, a windscreen - so the vocabulary is one function that paints a
 * rectangle and one that merges a list of them into a single mesh. Merging is
 * what keeps the map cheap: a district of eighty blocks and a stripe of lane
 * markings each cost one draw call, not eighty.
 *
 * Rectangles are painted with their own colour baked into a `color` attribute,
 * which is what lets one material draw a whole merged mesh.
 */

import { BufferAttribute, BufferGeometry, Color, Mesh, MeshBasicMaterial, PlaneGeometry } from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'

/**
 * One rectangle of the map, painted with a plain colour.
 *
 * @param options centre, size, rotation, depth and colour of the rectangle
 * @returns a geometry ready to be merged with its siblings
 */
export function paintedRectangle(options: {
  centerX: number
  centerY: number
  /** Size along the rectangle's own length, in world metres. */
  length: number
  /** Size across it, in world metres. */
  width: number
  /** Rotation around the depth axis, in radians, clockwise from the positive x axis. */
  angle?: number
  /** Where it sits in the stack; higher is nearer the camera. */
  z?: number
  color: string
}): BufferGeometry {
  const geometry = new PlaneGeometry(Math.max(options.length, 0.01), Math.max(options.width, 0.01)).toNonIndexed()
  if (options.angle) {
    geometry.rotateZ(options.angle)
  }
  geometry.translate(options.centerX, options.centerY, options.z ?? 0)
  return paintGeometry(geometry, options.color)
}

/**
 * One axis-aligned rectangle of the map.
 *
 * @param options bottom-left corner, size, depth and colour
 * @returns a geometry ready to be merged with its siblings
 */
export function paintedArea(options: {
  x: number
  y: number
  width: number
  height: number
  z?: number
  color: string
}): BufferGeometry {
  return paintedRectangle({
    centerX: options.x + options.width / 2,
    centerY: options.y + options.height / 2,
    length: options.width,
    width: options.height,
    z: options.z,
    color: options.color,
  })
}

/**
 * Bakes a colour into the vertices of a geometry.
 *
 * @param geometry geometry to paint, modified in place
 * @param color CSS colour
 * @returns the same geometry
 */
export function paintGeometry(geometry: BufferGeometry, color: string): BufferGeometry {
  const count = geometry.getAttribute('position').count
  const rgb = new Color(color)
  const colors = new Float32Array(count * 3)
  for (let index = 0; index < count; index += 1) {
    colors[index * 3] = rgb.r
    colors[index * 3 + 1] = rgb.g
    colors[index * 3 + 2] = rgb.b
  }
  geometry.setAttribute('color', new BufferAttribute(colors, 3))
  return geometry
}

/**
 * Merges painted rectangles into one geometry, so a whole layer costs one draw call.
 *
 * @param parts geometries built by this module
 * @returns the merged geometry, whose caller owns it and disposes it
 */
export function mergeParts(parts: readonly BufferGeometry[]): BufferGeometry {
  const merged = parts.length === 1 ? parts[0] : mergeGeometries([...parts], false)
  const geometry = merged ?? new BufferGeometry()
  geometry.computeBoundingSphere()
  return geometry
}

/**
 * Merges painted rectangles into one mesh.
 *
 * @param parts geometries built by this module
 * @param material material to draw them with, shared by every mesh of the scene
 * @param name name of the mesh, for a debugger
 * @returns the merged mesh, whose geometry the caller owns
 */
export function mergedMesh(parts: readonly BufferGeometry[], material: MeshBasicMaterial, name: string): Mesh {
  const geometry = mergeParts(parts)
  const mesh = new Mesh(geometry, material)
  mesh.name = name
  mesh.frustumCulled = false
  return mesh
}

/**
 * @param options colours and blending of the flat material
 * @returns a material that draws the colours baked into the geometry
 */
export function flatMaterial(options: {
  opacity?: number
  depthWrite?: boolean
} = {}): MeshBasicMaterial {
  return new MeshBasicMaterial({
    vertexColors: true,
    transparent: (options.opacity ?? 1) < 1,
    opacity: options.opacity ?? 1,
    depthWrite: options.depthWrite ?? true,
    toneMapped: false,
  })
}

/**
 * A rectangle covering a segment between two points.
 *
 * @param options the two ends, the width of the strip, the depth and the colour
 * @returns a geometry ready to be merged with its siblings
 */
export function paintedSegment(options: {
  x1: number
  y1: number
  x2: number
  y2: number
  width: number
  z?: number
  color: string
}): BufferGeometry {
  const dx = options.x2 - options.x1
  const dy = options.y2 - options.y1
  return paintedRectangle({
    centerX: (options.x1 + options.x2) / 2,
    centerY: (options.y1 + options.y2) / 2,
    length: Math.hypot(dx, dy),
    width: options.width,
    angle: Math.atan2(dy, dx),
    z: options.z,
    color: options.color,
  })
}
