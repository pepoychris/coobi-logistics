import type { Alert, Statistics, Vehicle } from '../../api/types'

/** A vehicle as `/api/v1/vehicles` and an `event:vehicle` frame report one. */
export function vehicle(overrides: Partial<Vehicle> = {}): Vehicle {
  return {
    vehicleId: 'TRUCK-00001',
    latitude: 39.4699,
    longitude: -0.3763,
    speed: 48.3,
    heading: 92,
    status: 'MOVING',
    lastUpdate: '2026-09-21T09:15:00.123Z',
    updatedAt: '2026-09-21T09:15:00.456Z',
    ...overrides,
  }
}

/** A speeding alert, with the metadata the stream processor stores for it. */
export function alert(overrides: Partial<Alert> = {}): Alert {
  return {
    id: 7,
    eventId: '2f8d3c1e-0b52-4a4f-9d76-8e13a3f0c111',
    vehicleId: 'TRUCK-00002',
    type: 'SPEEDING_DETECTED',
    severity: 'WARNING',
    occurredAt: '2026-09-21T09:15:00.123Z',
    createdAt: '2026-09-21T09:15:01.000Z',
    metadata: { speed: 137.2, threshold: 120 },
    ...overrides,
  }
}

export function statistics(overrides: Partial<Statistics> = {}): Statistics {
  return {
    processedEvents: 1_234_567,
    eventsPerSecond: 812,
    activeVehicles: 42,
    alertsGenerated: 9,
    uptimeSeconds: 3_725,
    ...overrides,
  }
}

/** A response the fetch of a test can answer with. */
export function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    json: async () => body,
  } as unknown as Response
}

/** Lets the pending microtasks and one turn of the event loop run. */
export function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0))
}
