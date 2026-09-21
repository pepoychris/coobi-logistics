<script setup lang="ts">
import { Cloud, Compass, Eye, Grid3x3, Layers, Palette, Route } from '@lucide/vue'
import { VISIBLE_COUNT_OPTIONS } from '../map/budget'
import type { FleetOptions } from '../map/fleetPresenter'
import type { NetworkDensity } from '../map/roadNetwork'
import { FLEET_THEMES } from '../map/theme'

/**
 * The deck that changes what the map draws.
 *
 * Every control here changes the view and nothing else: the fleet, the numbers
 * and the events come from the API whatever is selected. The controls are plain
 * inputs - a radio group for the count, checkboxes for the toggles, selects for
 * the palette and the density - so they are reachable by keyboard and readable
 * by assistive technology without any work of their own, and the styling is a
 * matter of the sheet.
 */
const props = defineProps<{
  options: FleetOptions
}>()

const emit = defineEmits<{ change: [Partial<FleetOptions>] }>()

const counts = VISIBLE_COUNT_OPTIONS

const themes = FLEET_THEMES

const densities: { value: NetworkDensity; label: string }[] = [
  { value: 'sparse', label: 'Sparse' },
  { value: 'regular', label: 'Regular' },
  { value: 'dense', label: 'Dense' },
]

function toggle(key: 'trails' | 'backgroundFleet' | 'followFleet'): void {
  emit('change', { [key]: !props.options[key] })
}
</script>

<template>
  <div class="deck">
    <fieldset class="deck__group">
      <legend class="deck__legend">
        <Eye class="deck__icon" aria-hidden="true" />
        Vehicles drawn
      </legend>
      <div class="segmented">
        <label
          v-for="count in counts"
          :key="count"
          class="segmented__option"
          :class="{ 'segmented__option--on': props.options.visibleCount === count }"
        >
          <input
            class="visually-hidden"
            type="radio"
            name="visible-vehicles"
            :value="count"
            :checked="props.options.visibleCount === count"
            @change="emit('change', { visibleCount: count })"
          />
          {{ count }}
        </label>
      </div>
    </fieldset>

    <fieldset class="deck__group">
      <legend class="deck__legend">
        <Layers class="deck__icon" aria-hidden="true" />
        Layers
      </legend>
      <div class="deck__toggles">
        <label class="switch" title="Draw a trail behind every moving vehicle">
          <input
            class="visually-hidden"
            type="checkbox"
            :checked="props.options.trails"
            @change="toggle('trails')"
          />
          <span class="switch__track" aria-hidden="true"><span class="switch__thumb" /></span>
          <Route class="deck__icon" aria-hidden="true" />
          Trails
        </label>
        <label class="switch" title="Keep the vehicles outside the budget as a faint cloud">
          <input
            class="visually-hidden"
            type="checkbox"
            :checked="props.options.backgroundFleet"
            @change="toggle('backgroundFleet')"
          />
          <span class="switch__track" aria-hidden="true"><span class="switch__thumb" /></span>
          <Cloud class="deck__icon" aria-hidden="true" />
          Faint fleet
        </label>
        <label class="switch" title="Keep the camera on the fleet as it drives">
          <input
            class="visually-hidden"
            type="checkbox"
            :checked="props.options.followFleet"
            @change="toggle('followFleet')"
          />
          <span class="switch__track" aria-hidden="true"><span class="switch__thumb" /></span>
          <Compass class="deck__icon" aria-hidden="true" />
          Follow
        </label>
      </div>
    </fieldset>

    <label class="deck__select">
      <span class="deck__legend">
        <Palette class="deck__icon" aria-hidden="true" />
        Palette
      </span>
      <select
        class="select"
        :value="props.options.themeId"
        title="Palette of the ground, the roads and the fleet"
        @change="emit('change', { themeId: ($event.target as HTMLSelectElement).value as FleetOptions['themeId'] })"
      >
        <option v-for="theme in themes" :key="theme.id" :value="theme.id">{{ theme.label }}</option>
      </select>
    </label>

    <label class="deck__select">
      <span class="deck__legend">
        <Grid3x3 class="deck__icon" aria-hidden="true" />
        District
      </span>
      <select
        class="select"
        :value="props.options.density"
        @change="emit('change', { density: ($event.target as HTMLSelectElement).value as NetworkDensity })"
      >
        <option v-for="density in densities" :key="density.value" :value="density.value">{{ density.label }}</option>
      </select>
    </label>
  </div>
</template>
