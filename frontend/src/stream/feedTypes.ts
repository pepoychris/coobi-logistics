import type { AlertSeverity, Vehicle } from '../api/types'

export type { Alert, AlertData, AlertSeverity, AlertType } from '../api/types'

/** The vehicle state the event stream carries; the same object the REST page returns. */
export type VehicleEvent = Vehicle

/**
 * One row of the live feed.
 *
 * `sequence` is what makes the rows of a session unique even when the backend
 * repeats a timestamp, and it is what the feed uses as its `v-for` key.
 */
export interface FeedEntry {
  id: string
  sequence: number
  /** Instant of the event, as the contract reports it; `null` when absent. */
  at: string | null
  vehicleId: string
  family: 'alert' | 'vehicle'
  label: string
  detail: string | null
  severity: AlertSeverity | null
  /** Whether this entry is an alert, which is what the feed styles differently. */
  isAlert: boolean
}
