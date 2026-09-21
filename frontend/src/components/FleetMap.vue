<script setup lang="ts">
import { Maximize2, X } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'

import type { Vehicle } from '../api/types'
import type { StreamStatus } from '../composables/useSseChannel'
import {
  FleetPresenter,
  normalizeFleetOptions,
  type FleetCounts,
  type FleetOptions,
} from '../map/fleetPresenter'
import { fleetTheme, themeCssVars } from '../map/theme'
import { fleetBudgetSummary, vehicleDetailRows } from '../map/vehicleDetails'
import { createWebglFleetRenderer } from '../map/webglRenderer'
import { statusLabel } from '../util/status'
import FleetControls from './FleetControls.vue'

/**
 * The fleet on a procedural map, drawn with three.js.
 *
 * The map is created once, when the panel mounts, and everything after that is
 * applied to the scene that already exists: a fleet moves the mini vehicles
 * that changed, a change of an option adds or removes the ones that crossed the
 * budget, and a gesture moves the camera. Nothing here decides what is true -
 * the vehicles are the ones the API reported, and the panel says how many of
 * them are drawn in full and how many are only a faint cloud.
 */
const props = defineProps<{
  vehicles: readonly Vehicle[]
  status: StreamStatus
}>()

const options = ref<FleetOptions>(normalizeFleetOptions({}))
const counts = ref<FleetCounts>({ tracked: 0, rendered: 0, background: 0, omitted: 0 })
const selectedVehicleId = ref<string | null>(null)
const unsupported = ref(false)

const frame = ref<HTMLElement | null>(null)
const surface = ref<HTMLElement | null>(null)
const presenter = shallowRef<FleetPresenter | null>(null)
const dragging = ref(false)

let observer: ResizeObserver | null = null
let pointerId: number | null = null
let lastX = 0
let lastY = 0
let travelled = 0

const theme = computed(() => fleetTheme(options.value.themeId))
const styles = computed(() => themeCssVars(theme.value))

const selectedVehicle = computed(
  () => props.vehicles.find((vehicle) => vehicle.vehicleId === selectedVehicleId.value) ?? null,
)
const details = computed(() => (selectedVehicle.value ? vehicleDetailRows(selectedVehicle.value) : []))
const movingCount = computed(() => props.vehicles.filter((vehicle) => vehicle.status === 'MOVING').length)
const stoppedCount = computed(() => props.vehicles.filter((vehicle) => vehicle.status === 'STOPPED').length)
const summary = computed(() => fleetBudgetSummary(counts.value))
const empty = computed(() => props.vehicles.length === 0)

function sizeOf(): { width: number; height: number } {
  const element = surface.value
  if (!element) {
    return { width: 1, height: 1 }
  }
  return {
    width: Math.max(1, Math.round(element.clientWidth)),
    height: Math.max(1, Math.round(element.clientHeight)),
  }
}

function applyOptions(next: Partial<FleetOptions>): void {
  options.value = normalizeFleetOptions(next, options.value)
  presenter.value?.setOptions(options.value)
  counts.value = presenter.value?.counts ?? counts.value
}

function fit(): void {
  presenter.value?.fitToFleet()
}

function clearSelection(): void {
  selectedVehicleId.value = null
}

function onPointerDown(event: PointerEvent): void {
  if (event.button !== 0) {
    return
  }
  pointerId = event.pointerId
  dragging.value = true
  travelled = 0
  lastX = event.clientX
  lastY = event.clientY
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
}

function onPointerMove(event: PointerEvent): void {
  if (!dragging.value || event.pointerId !== pointerId) {
    return
  }
  const dx = event.clientX - lastX
  const dy = event.clientY - lastY
  lastX = event.clientX
  lastY = event.clientY
  travelled += Math.abs(dx) + Math.abs(dy)
  presenter.value?.panBy(dx, dy)
}

function onPointerUp(event: PointerEvent): void {
  if (event.pointerId !== pointerId) {
    return
  }
  const element = frame.value
  const wasDrag = travelled > 4
  dragging.value = false
  pointerId = null
  ;(event.currentTarget as HTMLElement).releasePointerCapture?.(event.pointerId)
  if (wasDrag || !element) {
    return
  }
  const bounds = element.getBoundingClientRect()
  const picked = presenter.value?.pick(event.clientX - bounds.left, event.clientY - bounds.top) ?? null
  selectedVehicleId.value = picked?.vehicleId ?? null
}

function onWheel(event: WheelEvent): void {
  event.preventDefault()
  const element = frame.value
  if (!element) {
    return
  }
  const bounds = element.getBoundingClientRect()
  const factor = Math.min(1.6, Math.max(0.6, Math.exp(event.deltaY * 0.0014)))
  presenter.value?.zoomAt(event.clientX - bounds.left, event.clientY - bounds.top, factor)
}

function onKeydown(event: KeyboardEvent): void {
  const step = event.shiftKey ? 200 : 60
  switch (event.key) {
    case 'ArrowLeft':
      presenter.value?.panBy(step, 0)
      break
    case 'ArrowRight':
      presenter.value?.panBy(-step, 0)
      break
    case 'ArrowUp':
      presenter.value?.panBy(0, step)
      break
    case 'ArrowDown':
      presenter.value?.panBy(0, -step)
      break
    case '+':
    case '=':
      zoom(0.8)
      break
    case '-':
    case '_':
      zoom(1.25)
      break
    case 'f':
    case 'F':
      fit()
      break
    case 'Escape':
      clearSelection()
      break
    default:
      return
  }
  event.preventDefault()
}

function zoom(factor: number): void {
  const size = sizeOf()
  presenter.value?.zoomAt(size.width / 2, size.height / 2, factor)
}

onMounted(() => {
  const element = surface.value
  if (!element) {
    return
  }
  try {
    const renderer = createWebglFleetRenderer(element, theme.value)
    const instance = new FleetPresenter(renderer, options.value)
    instance.setSize(sizeOf())
    instance.update(props.vehicles)
    presenter.value = instance
    counts.value = instance.counts
  } catch (error) {
    unsupported.value = true
    console.warn('The fleet map could not start.', error)
  }

  if (typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(() => {
      presenter.value?.setSize(sizeOf())
      counts.value = presenter.value?.counts ?? counts.value
    })
    observer.observe(element)
  }
})

watch(
  () => props.vehicles,
  (vehicles) => {
    const instance = presenter.value
    if (!instance) {
      return
    }
    instance.update(vehicles)
    counts.value = instance.counts
    if (selectedVehicleId.value && !vehicles.some((vehicle) => vehicle.vehicleId === selectedVehicleId.value)) {
      // The backend stopped reporting the vehicle: the panel lets go of it
      // rather than describing a state that is no longer part of the fleet.
      clearSelection()
    }
  },
)

watch(selectedVehicleId, (vehicleId) => presenter.value?.setSelection(vehicleId))

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
  presenter.value?.dispose()
  presenter.value = null
})
</script>

<template>
  <section class="panel panel--map" :style="styles" aria-labelledby="fleet-map-heading">
    <header class="panel__header">
      <div class="panel__titles">
        <h2 id="fleet-map-heading" class="panel__title">Fleet map</h2>
        <p class="panel__subtitle">
          {{ props.vehicles.length }} tracked · {{ movingCount }} moving · {{ stoppedCount }} stopped ·
          {{ statusLabel(props.status) }}
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
        <span class="legend">
          <span class="legend__swatch legend__swatch--faint" aria-hidden="true" />
          Beyond budget
        </span>
        <button
          class="button button--icon"
          type="button"
          title="Centre the map on the whole fleet"
          aria-label="Centre the map on the whole fleet"
          :disabled="empty"
          @click="fit"
        >
          <Maximize2 class="icon" aria-hidden="true" />
        </button>
      </div>
    </header>

    <FleetControls :options="options" @change="applyOptions" />

    <div
      ref="frame"
      class="fleet-map"
      :class="{ 'fleet-map--dragging': dragging }"
      role="application"
      tabindex="0"
      aria-label="Live procedural map of the fleet. Use the arrow keys to pan, plus and minus to zoom, F to fit the fleet and Escape to clear the selection."
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @wheel="onWheel"
      @keydown="onKeydown"
    >
      <div ref="surface" class="fleet-map__surface" />

      <p class="chip chip--budget">{{ summary }}</p>

      <p v-if="unsupported" class="chip chip--state" role="alert">
        This browser cannot draw the procedural map: WebGL is not available. The KPIs and the event feed are unaffected.
      </p>
      <p v-else-if="empty" class="chip chip--state">Waiting for the first vehicle state of the stream.</p>

      <article v-if="selectedVehicle" class="vehicle-card">
        <header class="vehicle-card__header">
          <h3 class="vehicle-card__title">{{ selectedVehicle.vehicleId }}</h3>
          <button
            class="button button--icon button--ghost"
            type="button"
            title="Close the details of this vehicle"
            aria-label="Close the details of this vehicle"
            @click="clearSelection"
          >
            <X class="icon" aria-hidden="true" />
          </button>
        </header>
        <dl class="vehicle-card__list">
          <div v-for="row in details" :key="row.label" class="vehicle-card__row">
            <dt>{{ row.label }}</dt>
            <dd :title="row.hint">{{ row.value }}</dd>
          </div>
        </dl>
      </article>

      <p class="visually-hidden" aria-live="polite">{{ summary }}</p>
    </div>
  </section>
</template>
