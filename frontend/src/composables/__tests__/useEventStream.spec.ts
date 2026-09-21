import { effectScope } from 'vue'
import type { EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { CONNECTING, CLOSED, FakeEventSource } from '../../__tests__/support/fakeEventSource'
import { alert, flush, jsonResponse, vehicle } from '../../__tests__/support/fixtures'
import type { PageResponse, Vehicle } from '../../api/types'
import { FEED_CAPACITY, FLEET_RECONCILE_INTERVAL_MS, useEventStream } from '../useEventStream'
import type { EventStreamState } from '../useEventStream'

function fleetPage(content: Vehicle[]): PageResponse<Vehicle> {
  return {
    content,
    page: 0,
    size: 100,
    totalElements: content.length,
    totalPages: 1,
    first: true,
    last: true,
  }
}

describe('useEventStream', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let scope: EffectScope | null = null

  beforeEach(() => {
    FakeEventSource.reset()
    vi.stubGlobal('EventSource', FakeEventSource)
    fetchMock = vi.fn(async () => jsonResponse(fleetPage([vehicle({ vehicleId: 'TRUCK-SEED' })])))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    scope?.stop()
    scope = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  function start(): EventStreamState {
    const created = effectScope()
    scope = created
    return created.run(() => useEventStream()) as EventStreamState
  }

  it('opens the events stream of the API', () => {
    start()

    expect(FakeEventSource.last().url).toBe('/api/v1/stream/events')
  })

  it('seeds the fleet from the REST page', async () => {
    const state = start()

    await flush()

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/vehicles?page=0&size=100', expect.anything())
    expect(state.vehicles.value.map((entry) => entry.vehicleId)).toEqual(['TRUCK-SEED'])
    expect(state.loading.value).toBe(false)
  })

  it('adds an alert to the feed and a vehicle to the fleet', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()

    source.message('alert', alert({ vehicleId: 'TRUCK-9' }))
    source.message('vehicle', vehicle({ vehicleId: 'TRUCK-9', status: 'STOPPED' }))

    expect(state.entries.value).toHaveLength(2)
    expect(state.entries.value[0]).toMatchObject({ family: 'alert', label: 'SPEEDING', isAlert: true })
    expect(state.entries.value[1]).toMatchObject({ family: 'vehicle', label: 'LOCATION_UPDATED', isAlert: false })
    expect(state.vehicles.value).toHaveLength(1)
    expect(state.vehicles.value[0]).toMatchObject({ vehicleId: 'TRUCK-9', status: 'STOPPED' })
  })

  it('keeps only the newest entries, so the browser holds a bounded feed', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()

    const bursts = FEED_CAPACITY + 30
    for (let index = 0; index < bursts; index += 1) {
      source.message('vehicle', vehicle({ vehicleId: `TRUCK-${index}` }))
    }

    expect(state.entries.value).toHaveLength(FEED_CAPACITY)
    expect(state.entries.value[0].sequence).toBe(bursts - FEED_CAPACITY + 1)
    expect(state.entries.value.at(-1)?.sequence).toBe(bursts)
  })

  it('updates the state of a vehicle in place instead of adding it twice', () => {
    const state = start()
    const source = FakeEventSource.last()

    source.message('vehicle', vehicle({ vehicleId: 'TRUCK-1', speed: 40 }))
    source.message('vehicle', vehicle({ vehicleId: 'TRUCK-1', speed: 55 }))

    expect(state.vehicles.value).toHaveLength(1)
    expect(state.vehicles.value[0].speed).toBe(55)
    expect(state.vehicles.value[0].lastUpdate).toBe('2026-09-21T09:15:00.123Z')
  })

  it('drops a frame that is not the JSON the contract promises', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()

    source.rawMessage('vehicle', 'not json')
    source.rawMessage('alert', '{"unterminated": ')
    source.message('alert', alert())

    expect(state.entries.value).toHaveLength(1)
    expect(state.status.value).toBe('live')
  })

  it('keeps the last fleet while the browser retries, and says so', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()
    source.message('vehicle', vehicle({ vehicleId: 'TRUCK-1' }))

    source.fail(CONNECTING)

    expect(state.status.value).toBe('reconnecting')
    expect(state.vehicles.value).toHaveLength(1)
  })

  it('reports a closed connection as an error', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()

    source.fail(CLOSED)

    expect(state.status.value).toBe('error')
    expect(state.errorMessage.value).toMatch(/closed/)
  })

  it('reconciles the fleet with REST so a sampled stream cannot drift', async () => {
    vi.useFakeTimers()
    const state = start()
    await vi.advanceTimersByTimeAsync(0)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(FLEET_RECONCILE_INTERVAL_MS)

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(state.vehicles.value).toHaveLength(1)
  })

  it('empties the feed when the reader clears it, and keeps the fleet', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.message('vehicle', vehicle({ vehicleId: 'TRUCK-1' }))

    state.clear()

    expect(state.entries.value).toEqual([])
    expect(state.vehicles.value).toHaveLength(1)
  })
})
