import { describe, expect, it } from 'vitest'

import { vehicle } from '../../__tests__/support/fixtures'
import { createVehiclePopup } from '../vehiclePopup'

describe('createVehiclePopup', () => {
  it('shows the identity, the speed, the status and the last update', () => {
    const popup = createVehiclePopup(
      vehicle({ vehicleId: 'TRUCK-00042', speed: 12.34, status: 'STOPPED', lastUpdate: '2026-09-21T09:15:00Z' }),
    )

    expect(popup.dataset.vehicleId).toBe('TRUCK-00042')
    expect(popup.querySelector('.vehicle-popup__id')?.textContent).toBe('TRUCK-00042')

    const rows = [...popup.querySelectorAll('dl > div')].map((row) => [
      row.querySelector('dt')?.textContent,
      row.querySelector('dd')?.textContent,
    ])
    expect(rows).toEqual([
      ['Speed', '12.3 km/h'],
      ['Status', 'STOPPED'],
      ['Last update', expect.stringMatching(/^\d{2}:\d{2}:\d{2}$/)],
    ])
    expect(popup.querySelector('dl')?.title).toBe('2026-09-21T09:15:00Z')
  })

  it('writes the values as text, not as markup', () => {
    const popup = createVehiclePopup(vehicle({ vehicleId: '<img src=x onerror=alert(1)>' }))

    expect(popup.querySelector('img')).toBeNull()
    expect(popup.querySelector('.vehicle-popup__id')?.textContent).toBe('<img src=x onerror=alert(1)>')
  })

  it('shows a gap when the vehicle carries no instant', () => {
    const popup = createVehiclePopup(vehicle({ lastUpdate: '' }))
    const lastUpdate = [...popup.querySelectorAll('dl > div')].at(-1)

    expect(lastUpdate?.querySelector('dd')?.textContent).toBe('--:--:--')
  })
})
