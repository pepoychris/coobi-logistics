<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

import type { StreamStatus } from '../composables/useSseChannel'
import { FEED_CAPACITY } from '../composables/useEventStream'
import type { FeedEntry } from '../stream/feedTypes'
import { formatClock } from '../util/format'
import { statusLabel } from '../util/status'

/**
 * The live feed of the pipeline.
 *
 * The list is bounded by {@link FEED_CAPACITY} entries and the oldest row is the
 * one that leaves, so what the browser holds is a window on the stream and not a
 * transcript of it. Following the live edge is a reader's choice: a paused view
 * keeps receiving - the bound is what stops it from growing - and simply stops
 * chasing the end of the list.
 */
const props = defineProps<{
  entries: readonly FeedEntry[]
  status: StreamStatus
}>()

const emit = defineEmits<{ clear: [] }>()

const following = ref(true)
const list = ref<HTMLElement | null>(null)

watch(
  () => props.entries.length,
  async () => {
    if (!following.value) {
      return
    }
    await nextTick()
    const element = list.value
    if (element) {
      element.scrollTop = element.scrollHeight
    }
  },
)
</script>

<template>
  <section class="panel feed" aria-labelledby="feed-heading">
    <header class="panel__header">
      <div>
        <h2 id="feed-heading" class="panel__title">Live events</h2>
        <p class="panel__subtitle">
          Newest {{ FEED_CAPACITY }} of the events the processor stores · {{ props.entries.length }} shown ·
          {{ statusLabel(props.status) }}
        </p>
      </div>
      <div class="panel__actions">
        <button
          class="button button--ghost"
          type="button"
          :aria-pressed="following"
          :title="following ? 'Stop following the live edge' : 'Follow the live edge again'"
          @click="following = !following"
        >
          <svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              v-if="following"
              d="M9 6v12M15 6v12"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
            />
            <path v-else d="M8 5.5v13l10-6.5z" fill="currentColor" stroke="none" />
          </svg>
          {{ following ? 'Following' : 'Paused' }}
        </button>
        <button
          class="button button--ghost"
          type="button"
          title="Remove every event from this view"
          @click="emit('clear')"
        >
          <svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <path
              d="M6 7h12M9 7V5h6v2M8 7l1 12h6l1-12"
              fill="none"
              stroke="currentColor"
              stroke-width="1.6"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          Clear
        </button>
      </div>
    </header>

    <ol
      ref="list"
      class="feed__list"
      role="log"
      aria-live="polite"
      aria-relevant="additions"
      aria-label="Events of the pipeline, oldest first"
    >
      <li
        v-for="entry in props.entries"
        :key="entry.id"
        class="feed__row"
        :class="{ 'feed__row--alert': entry.isAlert }"
      >
        <time class="feed__time" :datetime="entry.at ?? undefined">{{ formatClock(entry.at) }}</time>
        <span class="feed__vehicle">{{ entry.vehicleId }}</span>
        <span class="feed__label">{{ entry.label }}</span>
        <span v-if="entry.isAlert" class="badge badge--alert">{{ entry.severity ?? 'ALERT' }}</span>
        <span v-if="entry.detail" class="feed__detail">{{ entry.detail }}</span>
      </li>
    </ol>

    <p v-if="props.entries.length === 0" class="feed__empty">
      No events yet. This view fills itself from the stream as the pipeline stores alerts and updates vehicles.
    </p>
  </section>
</template>
