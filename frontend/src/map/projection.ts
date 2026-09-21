/**
 * The geometry between what the API reports and what the map draws.
 *
 * The backend reports degrees; the scene is a plane in metres. The conversion
 * is an equirectangular projection anchored on one reference point, which is
 * exact enough for the tens of kilometres a simulated fleet covers and keeps
 * every later step - bounds, fitting, panning, zooming, picking - plain
 * arithmetic that a test can pin down without a browser or a GPU.
 *
 * `x` grows east, `y` grows north, which is also the way the scene is laid out:
 * the camera of the map looks down at it with `y` up the screen.
 */

/** A position in degrees, as the telemetry contract reports it. */
export interface GeoPoint {
  latitude: number
  longitude: number
}

/** A position in local metres, relative to the projection origin. */
export interface LocalPoint {
  x: number
  y: number
}

/** An axis-aligned rectangle in local metres. */
export interface Bounds {
  minX: number
  maxX: number
  minY: number
  maxY: number
}

/**
 * What the camera is looking at: the centre of the view and its half extents,
 * in local metres.
 */
export interface FleetView {
  centerX: number
  centerY: number
  halfWidth: number
  halfHeight: number
}

/** Metres in one degree of latitude, on the reference ellipsoid. */
export const METERS_PER_DEGREE_LATITUDE = 111_320

/** Closest the camera goes, as the half height of the view: 150 m of ground. */
export const MIN_HALF_HEIGHT = 150

/** Furthest the camera goes, as the half height of the view: 60 km of ground. */
export const MAX_HALF_HEIGHT = 60_000

/** How much room the fit leaves around the fleet, as a fraction of its span. */
export const FIT_PADDING = 0.18

/** The span a fleet of one vehicle is fitted to, so a single truck still has a district. */
export const MIN_FITTED_SPAN = 3_000

/**
 * @param latitude degrees north of the equator
 * @returns metres in one degree of longitude at that latitude
 */
export function metersPerDegreeLongitude(latitude: number): number {
  return METERS_PER_DEGREE_LATITUDE * Math.cos((latitude * Math.PI) / 180)
}

/**
 * @param point position in degrees
 * @param origin reference point of the projection
 * @returns the position in metres east and north of the origin
 */
export function projectPoint(point: GeoPoint, origin: GeoPoint): LocalPoint {
  return {
    x: (point.longitude - origin.longitude) * metersPerDegreeLongitude(origin.latitude),
    y: (point.latitude - origin.latitude) * METERS_PER_DEGREE_LATITUDE,
  }
}

/**
 * @param point position in local metres
 * @param origin reference point of the projection
 * @returns the position in degrees
 */
export function unprojectPoint(point: LocalPoint, origin: GeoPoint): GeoPoint {
  const scale = metersPerDegreeLongitude(origin.latitude)
  return {
    latitude: origin.latitude + point.y / METERS_PER_DEGREE_LATITUDE,
    longitude: origin.longitude + (scale === 0 ? 0 : point.x / scale),
  }
}

/**
 * @param points positions in local metres
 * @returns the rectangle that contains all of them, or `null` for an empty list
 */
export function boundsOf(points: readonly LocalPoint[]): Bounds | null {
  if (points.length === 0) {
    return null
  }
  let minX = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const point of points) {
    minX = Math.min(minX, point.x)
    maxX = Math.max(maxX, point.x)
    minY = Math.min(minY, point.y)
    maxY = Math.max(maxY, point.y)
  }
  return { minX, maxX, minY, maxY }
}

/**
 * @param bounds rectangle in local metres
 * @returns its centre
 */
export function centerOf(bounds: Bounds): LocalPoint {
  return { x: (bounds.minX + bounds.maxX) / 2, y: (bounds.minY + bounds.maxY) / 2 }
}

/**
 * The largest half extent that still fits a rectangle of a known shape.
 *
 * @param bounds rectangle to fit
 * @param widthPixels width of the viewport
 * @param heightPixels height of the viewport
 * @param minSpan smallest span the fit is allowed to use, for a tiny fleet
 * @returns half the height of a view that contains the rectangle with padding
 */
export function fitHalfHeight(
  bounds: Bounds,
  widthPixels: number,
  heightPixels: number,
  minSpan: number = MIN_FITTED_SPAN,
): number {
  const aspect = heightPixels > 0 && widthPixels > 0 ? widthPixels / heightPixels : 1
  const spanX = Math.max(bounds.maxX - bounds.minX, minSpan)
  const spanY = Math.max(bounds.maxY - bounds.minY, minSpan)
  const padded = 1 + FIT_PADDING
  const halfHeightForY = (spanY / 2) * padded
  const halfHeightForX = (spanX / 2 / aspect) * padded
  return clampHalfHeight(Math.max(halfHeightForY, halfHeightForX))
}

/**
 * @param halfHeight requested half height of the view
 * @returns the same value, kept inside the limits of the camera
 */
export function clampHalfHeight(halfHeight: number): number {
  if (!Number.isFinite(halfHeight)) {
    return MIN_FITTED_SPAN / 2
  }
  return Math.min(MAX_HALF_HEIGHT, Math.max(MIN_HALF_HEIGHT, halfHeight))
}

/**
 * A view that contains a fleet, centred on it.
 *
 * @param points positions of the fleet in local metres
 * @param widthPixels width of the viewport
 * @param heightPixels height of the viewport
 * @returns the view to draw, or `null` when there is nothing to fit
 */
export function fitView(
  points: readonly LocalPoint[],
  widthPixels: number,
  heightPixels: number,
): FleetView | null {
  const bounds = boundsOf(points)
  if (!bounds) {
    return null
  }
  const halfHeight = fitHalfHeight(bounds, widthPixels, heightPixels)
  const aspect = heightPixels > 0 && widthPixels > 0 ? widthPixels / heightPixels : 1
  const center = centerOf(bounds)
  return { centerX: center.x, centerY: center.y, halfWidth: halfHeight * aspect, halfHeight }
}

/**
 * @param view current view
 * @param widthPixels width of the viewport
 * @param heightPixels height of the viewport
 * @returns metres one pixel covers, which is what turns a gesture into a move
 */
export function metersPerPixel(view: FleetView, heightPixels: number): number {
  if (heightPixels <= 0) {
    return 1
  }
  return (view.halfHeight * 2) / heightPixels
}

/**
 * Moves the view by a drag, in pixels of the screen.
 *
 * A pointer that moves right drags the ground to the right, so the centre of
 * the view moves left, and a pointer that moves down pushes the centre up.
 *
 * @param view view to move
 * @param deltaXPixels horizontal movement of the pointer
 * @param deltaYPixels vertical movement of the pointer
 * @param heightPixels height of the viewport
 * @returns the moved view
 */
export function panView(view: FleetView, deltaXPixels: number, deltaYPixels: number, heightPixels: number): FleetView {
  const scale = metersPerPixel(view, heightPixels)
  return {
    ...view,
    centerX: view.centerX - deltaXPixels * scale,
    centerY: view.centerY + deltaYPixels * scale,
  }
}

/**
 * Scales the view, keeping a point of the ground under the same pixel.
 *
 * @param view view to scale
 * @param factor multiplier of the half extents; below 1 zooms in
 * @param anchorX ground position, in metres, that must stay where it is
 * @param anchorY ground position, in metres, that must stay where it is
 * @returns the scaled view
 */
export function zoomView(view: FleetView, factor: number, anchorX: number, anchorY: number): FleetView {
  const halfHeight = clampHalfHeight(view.halfHeight * factor)
  const applied = view.halfHeight === 0 ? 1 : halfHeight / view.halfHeight
  return {
    centerX: anchorX - (anchorX - view.centerX) * applied,
    centerY: anchorY - (anchorY - view.centerY) * applied,
    halfWidth: view.halfWidth * applied,
    halfHeight,
  }
}

/**
 * The ground position under one pixel of the view.
 *
 * @param view current view
 * @param offsetX pixels from the left edge of the viewport
 * @param offsetY pixels from the top edge of the viewport
 * @param widthPixels width of the viewport
 * @param heightPixels height of the viewport
 * @returns the position in local metres
 */
export function groundAt(
  view: FleetView,
  offsetX: number,
  offsetY: number,
  widthPixels: number,
  heightPixels: number,
): LocalPoint {
  const scaleX = widthPixels > 0 ? (view.halfWidth * 2) / widthPixels : 1
  const scaleY = heightPixels > 0 ? (view.halfHeight * 2) / heightPixels : 1
  return {
    x: view.centerX - view.halfWidth + offsetX * scaleX,
    y: view.centerY + view.halfHeight - offsetY * scaleY,
  }
}

/**
 * Whether a view contains a position.
 *
 * @param view view to test
 * @param point position in local metres
 * @param margin extra metres around the view that still count as inside
 * @returns whether the point is inside
 */
export function containsPoint(view: FleetView, point: LocalPoint, margin = 0): boolean {
  return (
    point.x >= view.centerX - view.halfWidth - margin &&
    point.x <= view.centerX + view.halfWidth + margin &&
    point.y >= view.centerY - view.halfHeight - margin &&
    point.y <= view.centerY + view.halfHeight + margin
  )
}
