import { effectScope } from 'vue'
import type { EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { CONNECTING, CLOSED, FakeEventSource } from '../../__tests__/support/fakeEventSource'
import { flush, jsonResponse, statistics } from '../../__tests__/support/fixtures'
import { useStatistics } from '../useStatistics'
import type { StatisticsState } from '../useStatistics'

describe('useStatistics', () => {
  let fetchMock: ReturnType<typeof vi.fn>
  let scope: EffectScope | null = null

  beforeEach(() => {
    FakeEventSource.reset()
    vi.stubGlobal('EventSource', FakeEventSource)
    fetchMock = vi.fn(async () => jsonResponse(statistics()))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    scope?.stop()
    scope = null
    vi.unstubAllGlobals()
  })

  function start(): StatisticsState {
    const created = effectScope()
    scope = created
    return created.run(() => useStatistics()) as StatisticsState
  }

  it('opens the statistics stream of the API', () => {
    start()

    expect(FakeEventSource.instances()).toHaveLength(1)
    expect(FakeEventSource.last().url).toBe('/api/v1/stream/statistics')
  })

  it('paints the KPIs from REST while the stream is still opening', async () => {
    const state = start()
    expect(state.loading.value).toBe(true)

    await flush()

    expect(state.statistics.value).toEqual(statistics())
    expect(state.loading.value).toBe(false)
    expect(state.status.value).toBe('loading')
  })

  it('follows the statistics frame of the stream and reports itself live', async () => {
    const state = start()
    const source = FakeEventSource.last()

    source.open()
    expect(state.status.value).toBe('live')
    expect(state.errorMessage.value).toBeNull()

    source.message('statistics', statistics({ processedEvents: 2_000_000, eventsPerSecond: 910 }))
    expect(state.statistics.value?.processedEvents).toBe(2_000_000)
    expect(state.statistics.value?.eventsPerSecond).toBe(910)
  })

  it('keeps the last values while the browser retries a dropped connection', async () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()
    source.message('statistics', statistics({ activeVehicles: 12 }))

    source.fail(CONNECTING)

    expect(state.status.value).toBe('reconnecting')
    expect(state.statistics.value?.activeVehicles).toBe(12)
  })

  it('reports a closed connection as an error instead of a live one', () => {
    const state = start()
    const source = FakeEventSource.last()
    source.open()

    source.fail(CLOSED)

    expect(state.status.value).toBe('error')
    expect(state.errorMessage.value).toMatch(/closed/)
  })

  it('does not replace a value the API reports as absent', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(statistics({ processedEvents: null, eventsPerSecond: null })))
    const state = start()

    await flush()

    expect(state.statistics.value?.processedEvents).toBeNull()
    expect(state.statistics.value?.eventsPerSecond).toBeNull()
  })

  it('reports a REST failure it cannot cover with a live stream', async () => {
    fetchMock.mockRejectedValueOnce(new Error('network down'))
    const state = start()

    await flush()

    expect(state.statistics.value).toBeNull()
    expect(state.loading.value).toBe(true)
    expect(state.errorMessage.value).toMatch(/network down/)
    expect(state.errorMessage.value).toContain('/api/v1/statistics')
  })

  it('clears the REST failure once the stream delivers a value', async () => {
    fetchMock.mockRejectedValueOnce(new Error('network down'))
    const state = start()
    await flush()

    FakeEventSource.last().open()
    FakeEventSource.last().message('statistics', statistics())

    expect(state.errorMessage.value).toBeNull()
    expect(state.loading.value).toBe(false)
  })

  it('opens a fresh connection when the reader asks for one', () => {
    const state = start()
    const first = FakeEventSource.last()

    state.reconnect()

    expect(first.closeCount).toBe(1)
    expect(FakeEventSource.instances()).toHaveLength(2)
    expect(FakeEventSource.last().url).toBe('/api/v1/stream/statistics')
  })
})
