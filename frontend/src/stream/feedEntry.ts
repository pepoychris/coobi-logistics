import type { Alert, AlertData, AlertType, FeedEntry, VehicleEvent } from './feedTypes'

/**
 * One frame of the event stream, as the feed shows it.
 *
 * The stream names its families (`alert`, `vehicle`) and carries the response of
 * the REST endpoint that owns the same object, so the two mappings below read
 * the fields the API documents and invent nothing: the label of an alert is its
 * type, the label of a vehicle event is the kind of event it is, and the detail
 * is the measured value of that event.
 */

/**
 * The short name of an alert type, for a feed row that has one line to say what
 * happened. The backend value stays the source of the label: an unknown type is
 * shown as it is rather than mapped to something plausible.
 */
const ALERT_LABEL: Record<AlertType, string> = {
  SPEEDING_DETECTED: 'SPEEDING',
  VEHICLE_STOPPED_DETECTED: 'VEHICLE_STOPPED',
}

/** What a `vehicle` frame means: the state of a vehicle was just updated. */
export const VEHICLE_EVENT_LABEL = 'LOCATION_UPDATED'

export function alertLabel(type: AlertType): string {
  return ALERT_LABEL[type] ?? type
}

function readAlertData(metadata: Alert['metadata']): AlertData {
  return metadata && typeof metadata === 'object' ? metadata : {}
}

/**
 * The one-line detail of an alert: what was measured and against which
 * threshold. The unit of the threshold follows the type of the alert, because
 * that is what the two numbers of `AlertData` mean.
 *
 * @param alert alert of the stream
 * @returns the detail, or `null` when the stored metadata carries no number
 */
export function alertDetail(alert: Alert): string | null {
  const data = readAlertData(alert.metadata)
  const measured = typeof data.speed === 'number' ? `${data.speed.toFixed(1)} km/h` : null
  if (measured === null) {
    return null
  }
  if (typeof data.threshold !== 'number') {
    return measured
  }
  const unit = alert.type === 'VEHICLE_STOPPED_DETECTED' ? 'm' : 'km/h'
  return `${measured} · threshold ${data.threshold} ${unit}`
}

/**
 * @param alert alert of an `event:alert` frame
 * @param sequence monotonic number of the entry within this session
 * @returns the feed entry of that alert
 */
export function alertEntry(alert: Alert, sequence: number): FeedEntry {
  return {
    id: `alert-${sequence}`,
    sequence,
    at: alert.occurredAt ?? null,
    vehicleId: alert.vehicleId,
    family: 'alert',
    label: alertLabel(alert.type),
    detail: alertDetail(alert),
    severity: alert.severity ?? null,
    isAlert: true,
  }
}

/**
 * @param vehicle vehicle state of an `event:vehicle` frame
 * @param sequence monotonic number of the entry within this session
 * @returns the feed entry of that telemetry event
 */
export function vehicleEntry(vehicle: VehicleEvent, sequence: number): FeedEntry {
  return {
    id: `vehicle-${sequence}`,
    sequence,
    at: vehicle.lastUpdate ?? null,
    vehicleId: vehicle.vehicleId,
    family: 'vehicle',
    label: VEHICLE_EVENT_LABEL,
    detail: `${vehicle.speed.toFixed(1)} km/h · ${vehicle.status}`,
    severity: null,
    isAlert: false,
  }
}
