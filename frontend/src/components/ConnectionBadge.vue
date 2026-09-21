<script setup lang="ts">
import { computed } from 'vue'

import type { StreamStatus } from '../composables/useSseChannel'
import { formatAge } from '../util/format'
import { statusLabel } from '../util/status'

/**
 * The state of the dashboard's connection, as one line.
 *
 * It is announced politely rather than assertively, because a reconnect every
 * few seconds is normal here and must not interrupt a reader; and when the
 * connection is not live it offers the one action that changes that.
 */
const props = defineProps<{
  status: StreamStatus
  /** When the last frame arrived, if one ever did. */
  lastMessageAt?: number | null
}>()

const emit = defineEmits<{ reconnect: [] }>()

const label = computed(() => statusLabel(props.status))
const canReconnect = computed(() => props.status !== 'live')
</script>

<template>
  <div class="connection" :class="`connection--${props.status}`" role="status" aria-live="polite">
    <span class="connection__dot" aria-hidden="true" />
    <span class="connection__label">{{ label }}</span>
    <span v-if="props.lastMessageAt" class="connection__age">{{ formatAge(props.lastMessageAt) }}</span>
    <button
      v-if="canReconnect"
      class="button button--ghost"
      type="button"
      title="Open the streams again"
      @click="emit('reconnect')"
    >
      <svg class="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
        <path
          d="M20 12a8 8 0 1 1-2.34-5.66M20 4v4h-4"
          fill="none"
          stroke="currentColor"
          stroke-width="1.8"
          stroke-linecap="round"
          stroke-linejoin="round"
        />
      </svg>
      Reconnect
    </button>
  </div>
</template>
