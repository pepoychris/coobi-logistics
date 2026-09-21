import type { StreamStatus } from '../composables/useSseChannel'

/**
 * How the state of a connection is said in words.
 *
 * The same four states are named the same way everywhere they appear - the
 * header badge, the KPI of the system status - so a reader never has to guess
 * whether two labels of the page mean the same thing.
 */
const STATUS_LABEL: Record<StreamStatus, string> = {
  loading: 'Connecting',
  live: 'Live',
  reconnecting: 'Reconnecting',
  error: 'Disconnected',
}

export function statusLabel(status: StreamStatus): string {
  return STATUS_LABEL[status]
}
