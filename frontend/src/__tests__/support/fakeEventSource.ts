/**
 * A stand-in for the browser's `EventSource`, so the stream logic of the
 * dashboard is tested by driving a connection frame by frame instead of by
 * waiting on a network and a clock.
 */

export const CONNECTING = 0
export const OPEN = 1
export const CLOSED = 2

type Listener = (event: Event) => void

export class FakeEventSource {
  static readonly CONNECTING = CONNECTING
  static readonly OPEN = OPEN
  static readonly CLOSED = CLOSED

  private static created: FakeEventSource[] = []

  static instances(): FakeEventSource[] {
    return FakeEventSource.created
  }

  static last(): FakeEventSource {
    const instance = FakeEventSource.created.at(-1)
    if (!instance) {
      throw new Error('no FakeEventSource has been created')
    }
    return instance
  }

  static reset(): void {
    FakeEventSource.created = []
  }

  readonly url: string
  readyState: number = CONNECTING
  closeCount = 0

  private readonly listeners = new Map<string, Set<Listener>>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.created.push(this)
  }

  addEventListener(type: string, listener: Listener): void {
    const listeners = this.listeners.get(type) ?? new Set<Listener>()
    listeners.add(listener)
    this.listeners.set(type, listeners)
  }

  removeEventListener(type: string, listener: Listener): void {
    this.listeners.get(type)?.delete(listener)
  }

  close(): void {
    this.closeCount += 1
    this.readyState = CLOSED
  }

  /** The server accepted the request: the opening frame of the contract. */
  open(): void {
    this.readyState = OPEN
    this.dispatch('open')
  }

  /**
   * The connection failed.
   *
   * @param readyState what the browser would report: `CONNECTING` while it is
   *        retrying on its own, `CLOSED` once it has given up
   */
  fail(readyState: number = CONNECTING): void {
    this.readyState = readyState
    this.dispatch('error')
  }

  /** One `event:` frame of the stream, with the object the API serializes. */
  message(eventName: string, payload: unknown): void {
    this.dispatch(eventName, JSON.stringify(payload))
  }

  /** One frame whose payload is not the JSON the contract promises. */
  rawMessage(eventName: string, payload: string): void {
    this.dispatch(eventName, payload)
  }

  private dispatch(type: string, data?: string): void {
    const event = { type, target: this, data } as unknown as Event
    for (const listener of [...(this.listeners.get(type) ?? [])]) {
      listener(event)
    }
  }
}
