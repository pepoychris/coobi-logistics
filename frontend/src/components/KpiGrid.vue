<script setup lang="ts">
import { Activity, Gauge, Radio, TriangleAlert, Truck } from '@lucide/vue'
import { computed } from 'vue'
import type { Component } from 'vue'

import type { Statistics } from '../api/types'
import type { StreamStatus } from '../composables/useSseChannel'
import { formatCount, formatUptime } from '../util/format'
import { statusLabel } from '../util/status'

/**
 * The numbers of the stack, every one of them read from the backend.
 *
 * Two rules are visible in the markup. A value the API reports as `null` is
 * drawn as a gap with the source named next to it, never as a zero that would
 * look measured. And while nothing has arrived yet the tiles show a placeholder
 * that is hidden from assistive technology, so a loading dashboard is not read
 * as a dashboard of zeros.
 */
const props = defineProps<{
  statistics: Statistics | null
  status: StreamStatus
  loading: boolean
}>()

interface Card {
  key: string
  label: string
  icon: Component
  value: string
  unit: string | null
  source: string
  unavailable: boolean
}

const cards = computed<Card[]>(() => {
  const statistics = props.statistics
  return [
    {
      key: 'processedEvents',
      label: 'Events processed',
      icon: Activity,
      value: formatCount(statistics?.processedEvents ?? null),
      unit: null,
      source: 'Kafka Streams counter of the stream processor',
      unavailable: statistics?.processedEvents == null,
    },
    {
      key: 'eventsPerSecond',
      label: 'Events per second',
      icon: Gauge,
      value: formatCount(statistics?.eventsPerSecond ?? null),
      unit: '/s',
      source: 'Rate between the two latest readings of that counter',
      unavailable: statistics?.eventsPerSecond == null,
    },
    {
      key: 'activeVehicles',
      label: 'Active vehicles',
      icon: Truck,
      value: formatCount(statistics?.activeVehicles ?? null),
      unit: null,
      source: 'Vehicles whose latest state is MOVING, counted in the database',
      unavailable: statistics == null,
    },
    {
      key: 'alertsGenerated',
      label: 'Alerts generated',
      icon: TriangleAlert,
      value: formatCount(statistics?.alertsGenerated ?? null),
      unit: null,
      source: 'Rows currently stored in the alerts table',
      unavailable: statistics == null,
    },
  ]
})

const systemStatus = computed(() => statusLabel(props.status))
const uptime = computed(() => formatUptime(props.statistics?.uptimeSeconds ?? null))
</script>

<template>
  <section class="kpis" aria-labelledby="kpis-heading">
    <h2 id="kpis-heading" class="visually-hidden">Live statistics of the stack</h2>
    <dl class="kpis__list" :aria-busy="props.loading">
      <div v-for="card in cards" :key="card.key" class="kpi" :data-unavailable="card.unavailable || undefined">
        <dt class="kpi__label">
          <component :is="card.icon" class="kpi__icon" aria-hidden="true" />
          {{ card.label }}
        </dt>
        <dd class="kpi__value">
          <template v-if="props.loading">
            <span class="skeleton" aria-hidden="true" />
            <span class="visually-hidden">{{ card.label }} is loading</span>
          </template>
          <template v-else>
            <span>{{ card.value }}</span>
            <span v-if="card.unit && !card.unavailable" class="kpi__unit">{{ card.unit }}</span>
          </template>
        </dd>
        <p class="kpi__source">{{ card.source }}</p>
      </div>

      <div class="kpi kpi--status" :class="`kpi--${props.status}`">
        <dt class="kpi__label">
          <Radio class="kpi__icon" aria-hidden="true" />
          System status
        </dt>
        <dd class="kpi__value">
          <span class="kpi__state">{{ systemStatus }}</span>
        </dd>
        <p class="kpi__source">Uptime of this API instance: {{ uptime }}</p>
      </div>
    </dl>
  </section>
</template>
