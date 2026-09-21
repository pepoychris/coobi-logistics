import * as L from 'leaflet'

import type { Vehicle, VehicleStatus } from '../api/types'
import { createVehiclePopup, updateVehiclePopup } from './vehiclePopup'
import type { VehicleMarker, VehicleMarkerFactory } from './vehicleLayer'

/**
 * The Leaflet side of the layer: one marker per vehicle, with the status of the
 * vehicle drawn as the colour of its dot.
 *
 * The icon is created once per status rather than once per update, the popup
 * element is created once per marker and rewritten in place, and the layer above
 * only calls this when a value it draws changed.
 */

const MARKER_SIZE = 16

function vehicleIcon(status: VehicleStatus): L.DivIcon {
  return L.divIcon({
    className: `vehicle-marker vehicle-marker--${status.toLowerCase()}`,
    html: `<span class="vehicle-marker__dot"></span>`,
    iconSize: [MARKER_SIZE, MARKER_SIZE],
    iconAnchor: [MARKER_SIZE / 2, MARKER_SIZE / 2],
    popupAnchor: [0, -MARKER_SIZE / 2],
  })
}

class LeafletVehicleMarker implements VehicleMarker {
  private readonly marker: L.Marker
  private readonly popup: HTMLElement
  private status: VehicleStatus

  constructor(vehicle: Vehicle) {
    this.status = vehicle.status
    this.popup = createVehiclePopup(vehicle)
    this.marker = L.marker([vehicle.latitude, vehicle.longitude], {
      icon: vehicleIcon(vehicle.status),
      title: vehicle.vehicleId,
      alt: vehicle.vehicleId,
      keyboard: true,
      riseOnHover: true,
    })
    this.marker.bindPopup(this.popup)
  }

  addTo(layer: L.LayerGroup): void {
    this.marker.addTo(layer)
  }

  setPosition(latitude: number, longitude: number): void {
    const current = this.marker.getLatLng()
    if (current.lat === latitude && current.lng === longitude) {
      return
    }
    this.marker.setLatLng([latitude, longitude])
  }

  setStatus(status: VehicleStatus): void {
    if (this.status === status) {
      return
    }
    this.status = status
    this.marker.setIcon(vehicleIcon(status))
  }

  setDetails(vehicle: Vehicle): void {
    updateVehiclePopup(this.popup, vehicle)
  }

  remove(): void {
    this.marker.remove()
  }
}

/**
 * @param layer group the markers are added to
 * @returns a factory the {@link VehicleLayer} drives
 */
export function createLeafletMarkerFactory(layer: L.LayerGroup): VehicleMarkerFactory {
  return {
    create(vehicle: Vehicle): VehicleMarker {
      const marker = new LeafletVehicleMarker(vehicle)
      marker.addTo(layer)
      return marker
    },
  }
}
