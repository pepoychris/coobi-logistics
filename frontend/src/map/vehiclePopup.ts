import type { Vehicle } from '../api/types'
import { formatClock, formatSpeedKph } from '../util/format'

/**
 * What a reader sees after clicking a vehicle: who it is, how fast it is going,
 * whether it is moving and when it was last heard from.
 *
 * The element is built rather than written as a string of HTML, so no value the
 * backend sends can be read as markup, and the four fields are a description
 * list because that is what they are: a term and its value.
 */

function field(label: string, value: string): HTMLElement {
  const row = document.createElement('div')
  const term = document.createElement('dt')
  const description = document.createElement('dd')
  term.textContent = label
  description.textContent = value
  row.append(term, description)
  return row
}

/**
 * The four values of the popup, written into the element that already holds
 * them.
 *
 * The element is built once per marker and rewritten afterwards, so a popup that
 * a reader has open follows the vehicle instead of being replaced under their
 * cursor.
 *
 * @param popup element built by {@link createVehiclePopup}
 * @param vehicle latest state of the vehicle
 */
export function updateVehiclePopup(popup: HTMLElement, vehicle: Vehicle): void {
  popup.dataset.vehicleId = vehicle.vehicleId

  const name = popup.querySelector('.vehicle-popup__id')
  if (name) {
    name.textContent = vehicle.vehicleId
  }

  const values = popup.querySelectorAll('dl > div > dd')
  values[0].textContent = formatSpeedKph(vehicle.speed)
  values[1].textContent = vehicle.status
  values[2].textContent = formatClock(vehicle.lastUpdate)

  const details = popup.querySelector('dl')
  if (details) {
    // The clock time is what a reader needs; the exact instant stays one hover
    // away instead of being spelled out in the popup.
    details.title = vehicle.lastUpdate ?? ''
  }
}

/**
 * @param vehicle latest state of the vehicle
 * @returns the popup of that vehicle, ready to be attached to its marker
 */
export function createVehiclePopup(vehicle: Vehicle): HTMLElement {
  const popup = document.createElement('div')
  popup.className = 'vehicle-popup'

  const name = document.createElement('p')
  name.className = 'vehicle-popup__id'

  const details = document.createElement('dl')
  details.append(field('Speed', ''), field('Status', ''), field('Last update', ''))

  popup.append(name, details)
  updateVehiclePopup(popup, vehicle)
  return popup
}
