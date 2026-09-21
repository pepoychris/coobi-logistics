<script setup lang="ts">
import { Truck } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import { apiTargetLabel } from './api/baseUrl'
import ConnectionBadge from './components/ConnectionBadge.vue'
import EventFeed from './components/EventFeed.vue'
import FleetMap from './components/FleetMap.vue'
import KpiGrid from './components/KpiGrid.vue'
import { useEventStream } from './composables/useEventStream'
import type { StreamStatus } from './composables/useSseChannel'
import { useStatistics } from './composables/useStatistics'
import { formatAge, formatClockAt } from './util/format'

/**
 * The operations console of the fleet: one statistics stream, one events
 * stream, and the three views that read them.
 *
 * Both streams are opened here and only here, so a page holds two connections
 * to the API no matter how many components consume them, and what the page
 * shows is a function of what the backend sent rather than of what a view would
 * like to display. The map, the numbers and the feed are three readings of the
 * same two streams.
 */
const statistics = useStatistics()
const events = useEventStream()

const apiTarget = apiTargetLabel()

const overallStatus = computed<StreamStatus>(() => {
  const states: StreamStatus[] = [statistics.status.value, events.status.value]
  if (states.includes('error')) {
    return 'error'
  }
  if (states.includes('reconnecting')) {
    return 'reconnecting'
  }
  return states.includes('loading') ? 'loading' : 'live'
})

const failureMessage = computed(() => statistics.errorMessage.value ?? events.errorMessage.value)

const lastMessageAt = computed(() => {
  const stamps = [statistics.lastMessageAt.value, events.lastMessageAt.value].filter(
    (stamp): stamp is number => typeof stamp === 'number',
  )
  return stamps.length === 0 ? null : Math.max(...stamps)
})

function reconnect(): void {
  statistics.reconnect()
  events.reconnect()
}

// A clock of its own, so that "12s ago" keeps counting while the streams are
// quiet. It ticks once a second and stops with the page.
const now = ref(Date.now())
let ticker: number | undefined

onMounted(() => {
  ticker = window.setInterval(() => {
    now.value = Date.now()
  }, 1_000)
})

onBeforeUnmount(() => {
  if (ticker !== undefined) {
    clearInterval(ticker)
  }
})
</script>

<template>
  <div class="app">
    <header class="app__header">
      <div class="app__identity">
        <span class="app__mark" aria-hidden="true"><Truck class="app__mark-icon" /></span>
        <div>
          <h1 class="app__title">Coobi Logistics</h1>
          <p class="app__subtitle">Fleet telemetry, from the broker to the browser</p>
        </div>
      </div>
      <div class="app__state">
        <span class="app__clock">{{ formatClockAt(now) }}</span>
        <ConnectionBadge :status="overallStatus" :last-message-at="lastMessageAt" @reconnect="reconnect" />
      </div>
    </header>

    <main class="app__main">
      <p v-if="overallStatus === 'error' && failureMessage" class="alert" role="alert">
        <strong>The dashboard is disconnected.</strong>
        {{ failureMessage }}. It is reading {{ apiTarget }}; start the stack, or point
        <code>VITE_API_BASE_URL</code> at the API.
      </p>

      <KpiGrid
        :statistics="statistics.statistics.value"
        :status="statistics.status.value"
        :loading="statistics.loading.value"
      />

      <div class="app__workspace">
        <FleetMap :vehicles="events.vehicles.value" :status="events.status.value" />
        <EventFeed :entries="events.entries.value" :status="events.status.value" @clear="events.clear" />
      </div>
    </main>

    <footer class="app__footer">
      <span>Last backend update: {{ formatAge(statistics.updatedAt.value ?? events.lastMessageAt.value, now) }}</span>
      <span>API: {{ apiTarget }}</span>
      <span>
        Every number, vehicle and alert on this page comes from the API and its two streams; the map is drawn from
        those positions, with no map provider involved.
      </span>
    </footer>
  </div>
</template>
