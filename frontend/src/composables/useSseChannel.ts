import { computed, getCurrentScope, onScopeDispose, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'

/**
 * One Server-Sent Events connection, and the state a view needs to describe it.
 *
 * The API of MVP-6 answers a stream with an open response: it sends a frame that
 * commits it, then `event:` frames as they happen, and it retries on its own
 * (the first frame carries `retry:3000`). The three states below are the ones a
 * reader of a live view can be told apart:
 *
 * - `loading`      nothing has been received yet, not even the opening frame;
 * - `live`         the connection is open and frames are arriving;
 * - `reconnecting` the connection dropped, or never opened, and the browser is
 *                  retrying on its own - the last known values are still shown,
 *                  which is why this is not an error;
 * - `error`        the connection is closed for good (the API refused it, the
 *                  browser stopped retrying, or the browser has no
 *                  `EventSource`), so nothing will arrive until the reader acts.
 */
export type StreamStatus = 'loading' | 'live' | 'reconnecting' | 'error'

/** `EventSource.CONNECTING`: the object is retrying, not finished. */
const EVENT_SOURCE_CONNECTING = 0

/** `EventSource.CLOSED`: the object has given up. */
const EVENT_SOURCE_CLOSED = 2

export interface SseChannel {
  /** State of the connection, as a view shows it. */
  status: Ref<StreamStatus>
  /** Why the connection is not live, or `null` while it is. */
  errorMessage: Ref<string | null>
  /** When the last frame or opening of this connection was seen. */
  lastMessageAt: Ref<number | null>
  isLive: ComputedRef<boolean>
  /**
   * Registers a listener for one event name of the stream. Listeners may be
   * registered after the connection is open - the dashboard does exactly that
   * while a component is being set up.
   */
  on: <T>(eventName: string, handler: (payload: T) => void) => void
  /** Closes the current connection and opens a new one, for the "reconnect" action. */
  reopen: () => void
  /** Closes the connection and stops the channel from reopening it. */
  close: () => void
}

/**
 * @param url endpoint of the stream, with its `/api/v1` prefix
 * @returns the channel, closed when the enclosing scope is disposed
 */
export function useSseChannel(url: string): SseChannel {
  const status = ref<StreamStatus>('loading')
  const errorMessage = ref<string | null>(null)
  const lastMessageAt = ref<number | null>(null)
  const handlers = new Map<string, Set<(payload: unknown) => void>>()

  let source: EventSource | null = null
  let closed = false
  let everLive = false

  function dispatch(eventName: string, raw: unknown): void {
    if (typeof raw !== 'string') {
      return
    }
    let payload: unknown
    try {
      payload = JSON.parse(raw)
    } catch {
      // A frame that is not the JSON the contract promises is dropped: one
      // unreadable frame must not end a connection that is otherwise fine.
      return
    }
    for (const handler of handlers.get(eventName) ?? []) {
      handler(payload)
    }
  }

  function listen(target: EventSource, eventName: string): void {
    target.addEventListener(eventName, (event) => {
      lastMessageAt.value = Date.now()
      dispatch(eventName, (event as MessageEvent).data)
    })
  }

  function onOpen(): void {
    everLive = true
    status.value = 'live'
    errorMessage.value = null
    lastMessageAt.value = Date.now()
  }

  function onError(event: Event): void {
    const readyState = (event.target as EventSource | null)?.readyState ?? source?.readyState ?? EVENT_SOURCE_CLOSED
    if (readyState === EVENT_SOURCE_CONNECTING) {
      status.value = 'reconnecting'
      errorMessage.value = everLive
        ? 'the connection dropped; the browser is reconnecting'
        : `cannot reach ${url}; the browser is retrying`
      return
    }
    status.value = 'error'
    errorMessage.value = everLive
      ? 'the stream is closed and the browser stopped reconnecting'
      : `cannot open the stream at ${url}`
  }

  function attach(): void {
    if (closed) {
      return
    }
    if (typeof EventSource === 'undefined') {
      status.value = 'error'
      errorMessage.value = 'this browser does not support Server-Sent Events'
      return
    }
    const next = new EventSource(url)
    source = next
    next.addEventListener('open', () => {
      if (source === next) {
        onOpen()
      }
    })
    next.addEventListener('error', (event) => {
      if (source === next) {
        onError(event)
      }
    })
    for (const eventName of handlers.keys()) {
      listen(next, eventName)
    }
  }

  function on<T>(eventName: string, handler: (payload: T) => void): void {
    const existing = handlers.get(eventName)
    if (existing) {
      existing.add(handler as (payload: unknown) => void)
      return
    }
    handlers.set(eventName, new Set([handler as (payload: unknown) => void]))
    if (source) {
      listen(source, eventName)
    }
  }

  function reopen(): void {
    close()
    closed = false
    status.value = everLive ? 'reconnecting' : 'loading'
    errorMessage.value = null
    attach()
  }

  function close(): void {
    closed = true
    if (source) {
      source.close()
      source = null
    }
  }

  attach()
  if (getCurrentScope()) {
    onScopeDispose(close)
  }

  return {
    status,
    errorMessage,
    lastMessageAt,
    isLive: computed(() => status.value === 'live'),
    on,
    reopen,
    close,
  }
}
