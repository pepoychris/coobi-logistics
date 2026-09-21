/**
 * The mini vehicle of the map: a delivery van, seen from above.
 *
 * Every vehicle of the map is this one mesh, drawn about sixteen pixels long -
 * cab, cargo box, windscreen, headlights, four wheels and the livery stripe of
 * the depot. The shape is built once per palette and per movement status and
 * shared by every vehicle that wears it, so a fleet of a hundred costs two
 * geometries and one material, and only the transform of a vehicle changes
 * while it drives.
 *
 * The model is built in metres and faces north, which is the convention the
 * heading of the contract is expressed in: a heading of ninety degrees is a
 * vehicle pointing east, and turning the mesh by `-heading` radians does it.
 */

import { Color, MeshBasicMaterial, type BufferGeometry } from 'three'

import type { VehicleStatus } from '../api/types'
import { flatMaterial, mergeParts, paintedArea } from './geometryParts'
import type { FleetTheme } from './theme'

/** Length of the vehicle the geometry describes, in metres. */
export const VEHICLE_LENGTH_METERS = 5.6

/** Width of the vehicle the geometry describes, in metres. */
export const VEHICLE_WIDTH_METERS = 2.2

/** How long the mini vehicle is drawn on screen, in CSS pixels. */
export const VEHICLE_MARKER_PIXELS = 17

/** The colours of one mini vehicle. */
export interface VehiclePaint {
  body: string
  cargo: string
  cab: string
  glass: string
  livery: string
  wheel: string
  light: string
  shadow: string
}

/** A little darker than a colour, for the shadow the vehicle casts. */
function shade(color: string, factor: number): string {
  return `#${new Color(color).multiplyScalar(factor).getHexString()}`
}

/**
 * @param status whether the vehicle is moving
 * @param theme palette of the map
 * @returns the colours of the mini vehicle that wears that status
 */
export function vehiclePaint(status: VehicleStatus, theme: FleetTheme): VehiclePaint {
  const moving = status === 'MOVING'
  const body = moving ? String(theme.moving) : String(theme.stopped)
  return {
    body,
    cargo: shade(body, moving ? 0.86 : 0.88),
    cab: shade(body, moving ? 1.06 : 1.04),
    glass: shade(String(theme.background), 1.9),
    livery: shade(body, 0.62),
    wheel: shade(String(theme.background), 0.55),
    light: moving ? '#fff3c4' : '#cfd6d3',
    shadow: shade(String(theme.background), 0.45),
  }
}

/**
 * Builds the geometry of one mini vehicle.
 *
 * All of it is in metres and faces north; the depth of each part is what keeps
 * the wheels under the body, the body under the cargo box and the markings on
 * top of everything.
 *
 * @param paint colours of the vehicle
 * @returns one merged geometry, ready for a shared material
 */
export function buildVehicleGeometry(paint: VehiclePaint): BufferGeometry {
  const halfLength = VEHICLE_LENGTH_METERS / 2
  const halfWidth = VEHICLE_WIDTH_METERS / 2
  const parts: BufferGeometry[] = []

  // The shadow, a little south-west of the vehicle, is what makes the fleet
  // read as standing on the ground rather than floating over it.
  parts.push(
    paintedArea({
      x: -halfLength + 0.2,
      y: -halfWidth - 0.35,
      width: VEHICLE_LENGTH_METERS * 1.02,
      height: VEHICLE_WIDTH_METERS * 1.12,
      z: 0.01,
      color: paint.shadow,
    }),
  )

  // Wheels: two axles, each wheel a little wider than the body so it shows.
  const wheelLength = 1.15
  const wheelWidth = 0.5
  for (const axle of [halfWidth * 0.72, -halfWidth * 0.72]) {
    for (const wheelX of [halfLength * 0.62, -halfLength * 0.66]) {
      parts.push(
        paintedArea({
          x: wheelX - wheelLength / 2,
          y: axle - wheelWidth / 2,
          width: wheelLength,
          height: wheelWidth,
          z: 0.02,
          color: paint.wheel,
        }),
      )
    }
  }

  // Cargo box: the long body of the van, two thirds of its length.
  parts.push(
    paintedArea({
      x: -halfLength + 0.15,
      y: -halfWidth + 0.05,
      width: 3.75,
      height: VEHICLE_WIDTH_METERS - 0.1,
      z: 0.03,
      color: paint.cargo,
    }),
  )

  // The cab, at the front, a shade lighter than the cargo box.
  parts.push(
    paintedArea({
      x: 1.35,
      y: -halfWidth + 0.14,
      width: 1.55,
      height: VEHICLE_WIDTH_METERS - 0.28,
      z: 0.04,
      color: paint.cab,
    }),
  )

  // Windscreen and the nose of the cab.
  parts.push(
    paintedArea({
      x: 2.35,
      y: -halfWidth + 0.28,
      width: 0.24,
      height: VEHICLE_WIDTH_METERS - 0.56,
      z: 0.05,
      color: paint.glass,
    }),
  )
  parts.push(
    paintedArea({
      x: 2.6,
      y: -halfWidth + 0.2,
      width: 0.16,
      height: VEHICLE_WIDTH_METERS - 0.4,
      z: 0.05,
      color: paint.body,
    }),
  )

  // Headlights, one per corner of the nose.
  for (const side of [1, -1]) {
    parts.push(
      paintedArea({
        x: 2.68,
        y: side * (halfWidth - 0.55) - 0.16,
        width: 0.3,
        height: 0.32,
        z: 0.06,
        color: paint.light,
      }),
    )
  }

  // The livery stripe of the depot, along the roof of the cargo box.
  parts.push(
    paintedArea({
      x: -halfLength + 0.35,
      y: -0.22,
      width: 3.3,
      height: 0.44,
      z: 0.06,
      color: paint.livery,
    }),
  )

  // The rear doors, as one line across the back of the van.
  parts.push(
    paintedArea({
      x: -halfLength + 0.02,
      y: -halfWidth + 0.24,
      width: 0.12,
      height: VEHICLE_WIDTH_METERS - 0.48,
      z: 0.06,
      color: paint.light,
    }),
  )

  return mergeParts(parts)
}

/** The geometries and the material of one palette, created once and shared. */
export interface VehicleGeometryLibrary {
  geometryFor(status: VehicleStatus): BufferGeometry
  readonly material: MeshBasicMaterial
  dispose(): void
}

/**
 * @param theme palette the vehicles wear
 * @returns the two geometries of the palette, with the material that draws them
 */
export function createVehicleGeometryLibrary(theme: FleetTheme): VehicleGeometryLibrary {
  const material = flatMaterial()
  const geometries = {
    MOVING: buildVehicleGeometry(vehiclePaint('MOVING', theme)),
    STOPPED: buildVehicleGeometry(vehiclePaint('STOPPED', theme)),
  } satisfies Record<VehicleStatus, BufferGeometry>
  return {
    geometryFor: (status) => geometries[status],
    material,
    dispose: () => {
      geometries.MOVING.dispose()
      geometries.STOPPED.dispose()
      material.dispose()
    },
  }
}
