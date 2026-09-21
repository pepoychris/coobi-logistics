import { describe, expect, it } from 'vitest'

import { vehicle } from '../../__tests__/support/fixtures'
import { compassPoint, fleetBudgetSummary, statusWord, vehicleDetailRows } from '../vehicleDetails'

describe('how a vehicle of the map is said in words', () => {
  it('names the two movement states of the contract', () => {
    expect(statusWord('MOVING')).toBe('Moving')
    expect(statusWord('STOPPED')).toBe('Stopped')
  })

  it('turns a heading into the point of the compass it aims at', () => {
    expect(compassPoint(0)).toBe('north')
    expect(compassPoint(90)).toBe('east')
    expect(compassPoint(225)).toBe('south-west')
    expect(compassPoint(359)).toBe('north')
    expect(compassPoint(-90)).toBe('west')
    expect(compassPoint(Number.NaN)).toBe('—')
  })

  it('lists the values of the selected vehicle, with a gap where one is missing', () => {
    const rows = vehicleDetailRows(
      vehicle({ vehicleId: 'TRUCK-42', speed: 48.3, heading: 92, status: 'MOVING' }),
    )

    expect(rows.map((row) => row.label)).toEqual([
      'Vehicle',
      'Status',
      'Speed',
      'Heading',
      'Last telemetry',
      'Read model',
    ])
    expect(rows[0].value).toBe('TRUCK-42')
    expect(rows[1].value).toBe('Moving')
    expect(rows[2].value).toBe('48.3 km/h')
    expect(rows[3].value).toBe('east (92°)')
    expect(rows.every((row) => row.hint.length > 0)).toBe(true)

    const missing = vehicleDetailRows(vehicle({ speed: Number.NaN, heading: Number.NaN }))
    expect(missing[2].value).toBe('—')
    expect(missing[3].value).toBe('— (—)')
  })

  it('reports what the budget draws and what it only acknowledges', () => {
    expect(fleetBudgetSummary({ tracked: 0, rendered: 0, background: 0, omitted: 0 })).toBe(
      'No vehicle state has arrived yet',
    )
    expect(fleetBudgetSummary({ tracked: 50, rendered: 50, background: 0, omitted: 0 })).toBe(
      '50 of 50 vehicles drawn in full',
    )
    expect(fleetBudgetSummary({ tracked: 400, rendered: 100, background: 300, omitted: 0 })).toBe(
      '100 of 400 vehicles drawn in full · 300 faint in the background',
    )
    expect(fleetBudgetSummary({ tracked: 30_000, rendered: 100, background: 20_000, omitted: 9_900 })).toBe(
      '100 of 30,000 vehicles drawn in full · 20,000 faint in the background · 9,900 beyond the cloud budget',
    )
  })
})
