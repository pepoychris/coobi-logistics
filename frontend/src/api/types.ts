/**
 * The response contracts of the logistics API (MVP-5) and of its streams
 * (MVP-6), as the dashboard reads them.
 *
 * The names here are the names on the wire: the services share one version-1
 * contract, and a client that renamed a field would be the only place where the
 * contract and the code disagree. The stream carries the very same objects, so
 * one type describes both what `/api/v1/vehicles` answers and what an
 * `event:vehicle` frame carries.
 */

/** Movement status reported by the latest telemetry event of a vehicle. */
export type VehicleStatus = 'MOVING' | 'STOPPED'

/** Latest known state of one vehicle, as `/api/v1/vehicles` and `event:vehicle` report it. */
export interface Vehicle {
  vehicleId: string
  /** Degrees of the latest position, positive north. */
  latitude: number
  /** Degrees of the latest position, positive east. */
  longitude: number
  /** Speed of the latest event, in kilometres per hour. */
  speed: number
  /** Heading of the latest event, in degrees clockwise from north. */
  heading: number
  status: VehicleStatus
  /** Instant of the latest telemetry event. */
  lastUpdate: string
  /** Instant this read model was last written. */
  updatedAt: string
}

/** Kind of alert the processor produces. */
export type AlertType = 'SPEEDING_DETECTED' | 'VEHICLE_STOPPED_DETECTED'

/** Severity of an alert; the contract has exactly one value today. */
export type AlertSeverity = 'WARNING'

/**
 * The `AlertData` of the alert contract, embedded as JSON.
 *
 * `threshold` means the speed limit for a speeding alert and the movement
 * threshold, in metres, for a stopped-vehicle alert, so a client that displays
 * it names it after the type of the alert.
 */
export interface AlertData {
  speed?: number
  threshold?: number
}

/** One alert, as `/api/v1/alerts` and `event:alert` report it. */
export interface Alert {
  id: number
  eventId: string
  vehicleId: string
  type: AlertType
  severity: AlertSeverity
  /** Instant of the telemetry event that triggered the alert. */
  occurredAt: string
  /** Instant the alert was stored. */
  createdAt: string
  metadata: AlertData | string | null
}

/**
 * Live state of the stack, as `/api/v1/statistics` and `event:statistics`
 * report it.
 *
 * `processedEvents` and `eventsPerSecond` are `null` when the source of the
 * value cannot be read. They are kept nullable on purpose: a client that
 * replaced the `null` with a number would display a value that looks live and
 * is not.
 */
export interface Statistics {
  processedEvents: number | null
  eventsPerSecond: number | null
  activeVehicles: number
  alertsGenerated: number
  uptimeSeconds: number
}

/** One page of a collection endpoint, as `/api/v1/vehicles` and `/api/v1/alerts` return it. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}
