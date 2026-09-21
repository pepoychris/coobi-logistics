import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'

import { describeError } from '../api/http'
import {
  ALERT_EVENT,
  eventsStreamUrl,
  fetchFleet,
  VEHICLE_EVENT,
} from '../api/logistics'
import type { Alert, Vehicle } from '../api/types'
import { alertEntry, vehicleEntry } from '../stream/feedEntry'
import type { FeedEntry } from '../stream/feedTypes'
import { useSseChannel } from './useSseChannel'
import type { StreamStatus } from './useSseChannel'

/**
 * The events a browser may see, as a bounded feed and as the fleet they update.
 *
 * One connection carries both, because both are the same stream: an `alert`
 * frame adds a row to the feed, and a `vehicle` frame adds a row and updates the
 * marker of that vehicle. The feed is capped at {@link FEED_CAPACITY} entries,
 * so an afternoon of telemetry costs the browser a fixed amount of memory and
 * not a growing one - the oldest row is the one that leaves.
 */

/** Most entries the feed keeps. Oldest first out, so the newest are always present. */
export const FEED_CAPACITY = 100

/**
 * How often the fleet is reconciled with the REST page.
 *
 * The stream is a sample on purpose: a burst is sampled rather than queued, and
 * two vehicle states that carry the same timestamp may leave one of them to the
 * REST view. The reconcile is what keeps the map honest about vehicles the
 * stream did not carry, and it is a page read every half minute - cheap enough
 * to be unremarkable, frequent enough that the map does not drift.
 */
export const FLEET_RECONCILE_INTERVAL_MS = 30_000

export interface EventStreamState {
  /** The feed, oldest entry first, never longer than {@link FEED_CAPACITY}. */
  entries: Ref<FeedEntry[]>
  /** The fleet, one entry per vehicle, as the seed and the stream together report it. */
  vehicles: Ref<Vehicle[]>
  vehicleCount: ComputedRef<number>
  alertCount: ComputedRef<number>
  status: Ref<StreamStatus>
  errorMessage: ComputedRef<string | null>
  lastMessageAt: Ref<number | null>
  loading: ComputedRef<boolean>
  reconnect: () => void
  clear: () => void
}

export function useEventStream(): EventStreamState {
  const channel = useSseChannel(eventsStreamUrl())
  const entries = ref<FeedEntry[]>([])
  const vehicles = ref<Vehicle[]>([])
  const seedFailure = ref<string | null>(null)
  const controller = new AbortController()
  let sequence = 0

  function append(entry: FeedEntry): void {
    const overflow = entries.value.length + 1 - FEED_CAPACITY
    entries.value = overflow > 0 ? [...entries.value.slice(overflow), entry] : [...entries.value, entry]
  }

  /**
   * Replaces the entry of a vehicle, or adds it when it is new. The stream
   * repeats the state of a vehicle rather than appending history, so the map has
   * one marker per vehicle id and the newest state wins.
   */
  function upsert(vehicle: Vehicle): void {
    const index = vehicles.value.findIndex((known) => known.vehicleId === vehicle.vehicleId)
    if (index === -1) {
      vehicles.value = [...vehicles.value, vehicle]
      return
    }
    vehicles.value = vehicles.value.map((known, position) => (position === index ? vehicle : known))
  }

  channel.on<Alert>(ALERT_EVENT, (alert) => {
    append(alertEntry(alert, ++sequence))
  })

  channel.on<Vehicle>(VEHICLE_EVENT, (vehicle) => {
    append(vehicleEntry(vehicle, ++sequence))
    upsert(vehicle)
  })

  function seedFleet(): void {
    void fetchFleet(controller.signal)
      .then((page) => {
        // Merged rather than replaced: telemetry that arrived while the page was
        // in flight is newer than the page and must not be overwritten by it.
        for (const vehicle of page) {
          upsert(vehicle)
        }
        seedFailure.value = null
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) {
          seedFailure.value = describeError(failure)
        }
      })
  }

  seedFleet()
  const reconcile = setInterval(seedFleet, FLEET_RECONCILE_INTERVAL_MS)

  if (getCurrentScope()) {
    onScopeDispose(() => {
      clearInterval(reconcile)
      controller.abort()
    })
  }

  return {
    entries,
    vehicles,
    vehicleCount: computed(() => vehicles.value.length),
    alertCount: computed(() => entries.value.filter((entry) => entry.isAlert).length),
    status: channel.status,
    errorMessage: computed(() => channel.errorMessage.value ?? (vehicles.value.length === 0 ? seedFailure.value : null)),
    lastMessageAt: channel.lastMessageAt,
    loading: computed(() => vehicles.value.length === 0 && entries.value.length === 0),
    reconnect: channel.reopen,
    clear: () => {
      entries.value = []
    },
  }
}
