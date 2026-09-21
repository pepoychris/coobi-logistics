<script setup lang="ts">
import { RotateCw } from '@lucide/vue'
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
      <RotateCw class="icon" aria-hidden="true" />
      Reconnect
    </button>
  </div>
</template>
