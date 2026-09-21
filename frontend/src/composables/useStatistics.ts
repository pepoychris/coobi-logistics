import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'

import { describeError } from '../api/http'
import { fetchStatistics, STATISTICS_EVENT, statisticsStreamUrl } from '../api/logistics'
import type { Statistics } from '../api/types'
import { useSseChannel } from './useSseChannel'
import type { StreamStatus } from './useSseChannel'

/**
 * The live statistics of the stack.
 *
 * The values come from the backend twice over, for two different reasons: the
 * REST endpoint paints the page once, and the stream then re-sends the same
 * object every interval, which is what makes the KPIs move without a poll of
 * this component's own. Nothing here computes a statistic locally, and a field
 * the API reports as `null` stays `null` through to the card that shows it.
 */
export interface StatisticsState {
  /** Latest object the backend reported, or `null` while nothing has arrived. */
  statistics: Ref<Statistics | null>
  /** When that object was received. */
  updatedAt: Ref<number | null>
  status: Ref<StreamStatus>
  errorMessage: ComputedRef<string | null>
  lastMessageAt: Ref<number | null>
  /** True until the first object arrives, from either source. */
  loading: ComputedRef<boolean>
  reconnect: () => void
}

export function useStatistics(): StatisticsState {
  const channel = useSseChannel(statisticsStreamUrl())
  const statistics = ref<Statistics | null>(null)
  const updatedAt = ref<number | null>(null)
  const seedFailure = ref<string | null>(null)
  const controller = new AbortController()

  function accept(next: Statistics): void {
    statistics.value = next
    updatedAt.value = Date.now()
    seedFailure.value = null
  }

  channel.on<Statistics>(STATISTICS_EVENT, accept)

  // The first paint should not wait for the next tick of the stream, and a
  // browser whose stream never opens still deserves the values the API can
  // answer over REST - with a state that says so.
  void fetchStatistics(controller.signal)
    .then(accept)
    .catch((failure: unknown) => {
      if (!controller.signal.aborted) {
        seedFailure.value = describeError(failure)
      }
    })

  if (getCurrentScope()) {
    onScopeDispose(() => controller.abort())
  }

  return {
    statistics,
    updatedAt,
    status: channel.status,
    errorMessage: computed(() => channel.errorMessage.value ?? (statistics.value ? null : seedFailure.value)),
    lastMessageAt: channel.lastMessageAt,
    loading: computed(() => statistics.value === null),
    reconnect: channel.reopen,
  }
}
