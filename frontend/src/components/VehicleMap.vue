<script setup lang="ts">
import * as L from 'leaflet'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'

import type { Vehicle } from '../api/types'
import { createLeafletMarkerFactory } from '../map/leafletMarkers'
import { VehicleLayer } from '../map/vehicleLayer'

/**
 * The fleet on a map.
 *
 * The map is created once, and every update after that moves the markers that
 * exist and adds the ones for vehicles that appeared. Leaflet is never asked to
 * lay the map out again, which is what keeps it responsive with a fleet of a few
 * hundred vehicles: the cost of a tick is the number of vehicles whose state
 * changed and not the size of the fleet.
 */
const props = defineProps<{
  vehicles: readonly Vehicle[]
}>()

/** Somewhere in the middle of the fleet until the first vehicle places it. */
const INITIAL_CENTER: L.LatLngExpression = [39.4699, -0.3763]
const INITIAL_ZOOM = 11

const container = ref<HTMLElement | null>(null)
const map = shallowRef<L.Map | null>(null)
let markers: L.LayerGroup | null = null
let layer: VehicleLayer | null = null
let resizeObserver: ResizeObserver | null = null
let centredOnFleet = false

const movingCount = computed(() => props.vehicles.filter((vehicle) => vehicle.status === 'MOVING').length)
const stoppedCount = computed(() => props.vehicles.filter((vehicle) => vehicle.status === 'STOPPED').length)

function fitToFleet(): void {
  const instance = map.value
  if (!instance || props.vehicles.length === 0) {
    return
  }
  const bounds = L.latLngBounds(
    props.vehicles.map((vehicle) => [vehicle.latitude, vehicle.longitude] as [number, number]),
  )
  instance.fitBounds(bounds, { padding: [32, 32], maxZoom: 14 })
  centredOnFleet = true
}

function applyFleet(vehicles: readonly Vehicle[]): void {
  layer?.sync(vehicles)
  if (!centredOnFleet && vehicles.length > 0) {
    fitToFleet()
  }
}

onMounted(() => {
  const element = container.value
  if (!element) {
    return
  }
  const instance = L.map(element, {
    center: INITIAL_CENTER,
    zoom: INITIAL_ZOOM,
    zoomControl: true,
    attributionControl: true,
    worldCopyJump: true,
  })
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  }).addTo(instance)

  markers = L.layerGroup().addTo(instance)
  layer = new VehicleLayer(createLeafletMarkerFactory(markers))
  map.value = instance
  applyFleet(props.vehicles)

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => instance.invalidateSize())
    resizeObserver.observe(element)
  }
})

watch(
  () => props.vehicles,
  (vehicles) => applyFleet(vehicles),
)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
  layer?.clear()
  layer = null
  markers = null
  map.value?.remove()
  map.value = null
})
</script>

<template>
  <section class="panel map" aria-labelledby="map-heading">
    <header class="panel__header">
      <div>
        <h2 id="map-heading" class="panel__title">Fleet map</h2>
        <p class="panel__subtitle">
          {{ props.vehicles.length }} vehicles · {{ movingCount }} moving · {{ stoppedCount }} stopped
        </p>
      </div>
      <div class="panel__actions">
        <span class="legend">
          <span class="legend__swatch legend__swatch--moving" aria-hidden="true" />
          Moving
        </span>
        <span class="legend">
          <span class="legend__swatch legend__swatch--stopped" aria-hidden="true" />
          Stopped
        </span>
        <button
          class="button button--icon"
          type="button"
          aria-label="Centre the map on the fleet"
          title="Centre the map on the fleet"
          :disabled="props.vehicles.length === 0"
          @click="fitToFleet"
        >
          <svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M12 3v3M12 18v3M3 12h3M18 12h3M12 9.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z"
              fill="none"
              stroke="currentColor"
              stroke-width="1.7"
              stroke-linecap="round"
            />
          </svg>
        </button>
      </div>
    </header>

    <div class="map__frame">
      <div ref="container" class="map__canvas" role="region" aria-label="Live map of the fleet" />
      <p v-if="props.vehicles.length === 0" class="map__empty">Waiting for the first vehicle state of the stream.</p>
    </div>
  </section>
</template>
