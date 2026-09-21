import { apiUrl } from './baseUrl'
import { getJson } from './http'
import type { PageResponse, Statistics, Vehicle } from './types'

/**
 * The endpoints of the logistics API the dashboard reads.
 *
 * The dashboard is a reader: it fetches the history over REST and follows the
 * live edge over the two streams, and it writes nothing.
 */

/**
 * Vehicles one page of the fleet may carry. It is the maximum page size the API
 * accepts (`VehicleQueryService.MAX_PAGE_SIZE`), so the seeded fleet is one
 * request and not a walk over pages.
 */
export const FLEET_PAGE_SIZE = 100

/** The statistics stream re-sends this object every interval the API is configured with. */
export const STATISTICS_EVENT = 'statistics'

/** The event stream carries these two families, named as the API names them. */
export const ALERT_EVENT = 'alert'
export const VEHICLE_EVENT = 'vehicle'

export function vehiclesUrl(page = 0, size = FLEET_PAGE_SIZE): string {
  return `${apiUrl('/vehicles')}?page=${page}&size=${size}`
}

export function statisticsUrl(): string {
  return apiUrl('/statistics')
}

export function eventsStreamUrl(): string {
  return apiUrl('/stream/events')
}

export function statisticsStreamUrl(): string {
  return apiUrl('/stream/statistics')
}

/**
 * @param signal cancels the request when the view goes away
 * @returns one page of the fleet, newest telemetry first
 */
export async function fetchFleet(signal?: AbortSignal): Promise<Vehicle[]> {
  const page = await getJson<PageResponse<Vehicle>>(vehiclesUrl(), signal)
  return page.content
}

/**
 * @param signal cancels the request when the view goes away
 * @returns the live statistics, as the API observed them at that moment
 */
export function fetchStatistics(signal?: AbortSignal): Promise<Statistics> {
  return getJson<Statistics>(statisticsUrl(), signal)
}
