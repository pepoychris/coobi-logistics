import { describe, expect, it } from 'vitest'

import {
  boundsOf,
  centerOf,
  clampHalfHeight,
  containsPoint,
  fitView,
  groundAt,
  MAX_HALF_HEIGHT,
  metersPerDegreeLongitude,
  metersPerPixel,
  MIN_FITTED_SPAN,
  MIN_HALF_HEIGHT,
  panView,
  projectPoint,
  unprojectPoint,
  zoomView,
  type FleetView,
} from '../projection'

/** The reference point of the simulated fleet, roughly the centre of Valencia. */
const ORIGIN = { latitude: 39.4699, longitude: -0.3763 }

describe('the projection of the map', () => {
  it('knows how long a degree is, on both axes', () => {
    expect(metersPerDegreeLongitude(0)).toBeCloseTo(111_320, 0)
    expect(metersPerDegreeLongitude(60)).toBeCloseTo(55_660, 0)
  })

  it('places a position in metres north and east of the reference point', () => {
    const north = projectPoint({ latitude: ORIGIN.latitude + 0.01, longitude: ORIGIN.longitude }, ORIGIN)
    const east = projectPoint({ latitude: ORIGIN.latitude, longitude: ORIGIN.longitude + 0.01 }, ORIGIN)

    expect(north.y).toBeCloseTo(1_113.2, 1)
    expect(north.x).toBeCloseTo(0, 6)
    expect(east.x).toBeCloseTo(859.3, 1)
    expect(east.y).toBeCloseTo(0, 6)
  })

  it('turns its own result back into degrees', () => {
    const point = { latitude: ORIGIN.latitude + 0.02, longitude: ORIGIN.longitude - 0.03 }

    const round = unprojectPoint(projectPoint(point, ORIGIN), ORIGIN)

    expect(round.latitude).toBeCloseTo(point.latitude, 9)
    expect(round.longitude).toBeCloseTo(point.longitude, 9)
  })

  it('measures a rectangle around a fleet, and nothing around an empty one', () => {
    expect(boundsOf([])).toBeNull()

    const bounds = boundsOf([
      { x: -10, y: 4 },
      { x: 30, y: -6 },
      { x: 5, y: 20 },
    ])

    expect(bounds).toEqual({ minX: -10, maxX: 30, minY: -6, maxY: 20 })
    expect(centerOf(bounds!)).toEqual({ x: 10, y: 7 })
  })

  it('fits a fleet inside the view, with room around it', () => {
    const points = [
      { x: -1_000, y: -500 },
      { x: 1_000, y: 500 },
    ]

    const view = fitView(points, 900, 600)

    expect(view).not.toBeNull()
    expect(view!.centerX).toBe(0)
    expect(view!.centerY).toBe(0)
    // Two kilometres of fleet, 18% of padding, through a 1.5:1 viewport: the
    // height of the view is what the taller side needs, and the width follows
    // from the shape of the viewport.
    expect(view!.halfHeight).toBeCloseTo(1_770, 0)
    expect(view!.halfWidth).toBeCloseTo(2_655, 0)
    for (const point of points) {
      expect(containsPoint(view!, point)).toBe(true)
    }
  })

  it('gives a single vehicle a district to stand in rather than a view of nothing', () => {
    const view = fitView([{ x: 40, y: 40 }], 900, 600)

    expect(view!.halfHeight).toBeCloseTo((MIN_FITTED_SPAN / 2) * 1.18, 0)
    expect(view!.centerX).toBe(40)
  })

  it('keeps the camera inside its own limits', () => {
    expect(clampHalfHeight(1)).toBe(MIN_HALF_HEIGHT)
    expect(clampHalfHeight(1_000_000)).toBe(MAX_HALF_HEIGHT)
    expect(clampHalfHeight(Number.NaN)).toBe(MIN_FITTED_SPAN / 2)
    expect(clampHalfHeight(800)).toBe(800)
  })

  it('drags the ground with the pointer, in the direction the pointer moved', () => {
    const view: FleetView = { centerX: 0, centerY: 0, halfWidth: 1_500, halfHeight: 1_000 }

    const right = panView(view, 100, 0, 600)
    const down = panView(view, 0, 100, 600)

    // 2,000 metres over 600 pixels: 100 pixels are 333 metres of ground.
    expect(right.centerX).toBeCloseTo(-333.33, 1)
    expect(right.centerY).toBe(0)
    expect(down.centerY).toBeCloseTo(333.33, 1)
    expect(metersPerPixel(view, 600)).toBeCloseTo(3.3333, 3)
  })

  it('zooms around the point of the ground under the pointer', () => {
    const view: FleetView = { centerX: 0, centerY: 0, halfWidth: 1_500, halfHeight: 1_000 }
    const anchor = { x: 600, y: 400 }

    const zoomed = zoomView(view, 0.5, anchor.x, anchor.y)

    expect(zoomed.halfHeight).toBe(500)
    expect((anchor.x - zoomed.centerX) / zoomed.halfWidth).toBeCloseTo((anchor.x - view.centerX) / view.halfWidth, 9)
    expect((anchor.y - zoomed.centerY) / zoomed.halfHeight).toBeCloseTo((anchor.y - view.centerY) / view.halfHeight, 9)
  })

  it('stops zooming out when the camera is already at its limit', () => {
    const view: FleetView = { centerX: 0, centerY: 0, halfWidth: 90_000, halfHeight: MAX_HALF_HEIGHT }

    const zoomed = zoomView(view, 2, 0, 0)

    expect(zoomed.halfHeight).toBe(MAX_HALF_HEIGHT)
  })

  it('says which metre of ground one pixel of the map is over', () => {
    const view: FleetView = { centerX: 100, centerY: 50, halfWidth: 900, halfHeight: 600 }

    expect(groundAt(view, 0, 0, 1_800, 1_200)).toEqual({ x: -800, y: 650 })
    expect(groundAt(view, 900, 600, 1_800, 1_200)).toEqual({ x: 100, y: 50 })
    expect(groundAt(view, 1_800, 1_200, 1_800, 1_200)).toEqual({ x: 1_000, y: -550 })
  })

  it('counts a point just outside the view when a margin of grace is given', () => {
    const view: FleetView = { centerX: 0, centerY: 0, halfWidth: 100, halfHeight: 100 }

    expect(containsPoint(view, { x: 120, y: 0 })).toBe(false)
    expect(containsPoint(view, { x: 120, y: 0 }, 40)).toBe(true)
  })
})
