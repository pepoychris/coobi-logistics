import { describe, expect, it } from 'vitest'

import { alert, vehicle } from '../../__tests__/support/fixtures'
import { alertDetail, alertEntry, alertLabel, vehicleEntry } from '../feedEntry'

describe('alertLabel', () => {
  it('names the two alert types of the contract', () => {
    expect(alertLabel('SPEEDING_DETECTED')).toBe('SPEEDING')
    expect(alertLabel('VEHICLE_STOPPED_DETECTED')).toBe('VEHICLE_STOPPED')
  })
})

describe('alertDetail', () => {
  it('reads the measured speed and the threshold of a speeding alert', () => {
    const entry = alertDetail(alert())
    expect(entry).toBe('137.2 km/h · threshold 120 km/h')
  })

  it('names the threshold of a stopped vehicle in metres', () => {
    const stopped = alert({
      type: 'VEHICLE_STOPPED_DETECTED',
      metadata: { speed: 0, threshold: 50 },
    })
    expect(alertDetail(stopped)).toBe('0.0 km/h · threshold 50 m')
  })

  it('reports no detail when the stored metadata carries no measurement', () => {
    expect(alertDetail(alert({ metadata: 'not json at all' }))).toBeNull()
    expect(alertDetail(alert({ metadata: { threshold: 120 } }))).toBeNull()
  })

  it('reports the measurement alone when the threshold is absent', () => {
    expect(alertDetail(alert({ metadata: { speed: 130 } }))).toBe('130.0 km/h')
  })
})

describe('alertEntry', () => {
  it('builds an alert row from the frame of the stream', () => {
    const entry = alertEntry(alert(), 3)

    expect(entry).toMatchObject({
      id: 'alert-3',
      sequence: 3,
      vehicleId: 'TRUCK-00002',
      family: 'alert',
      label: 'SPEEDING',
      severity: 'WARNING',
      isAlert: true,
      at: '2026-09-21T09:15:00.123Z',
    })
  })
})

describe('vehicleEntry', () => {
  it('builds a telemetry row from the frame of the stream', () => {
    const entry = vehicleEntry(vehicle(), 4)

    expect(entry).toMatchObject({
      id: 'vehicle-4',
      sequence: 4,
      vehicleId: 'TRUCK-00001',
      family: 'vehicle',
      label: 'LOCATION_UPDATED',
      detail: '48.3 km/h · MOVING',
      severity: null,
      isAlert: false,
      at: '2026-09-21T09:15:00.123Z',
    })
  })
})
