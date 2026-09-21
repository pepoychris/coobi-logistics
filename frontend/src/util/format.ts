/**
 * How a value of the backend is shown to a reader.
 *
 * Every formatter has an answer for a value that is absent, and that answer is
 * a visible gap rather than a stand-in number: a statistic whose source cannot
 * be read is reported as `null` by the API (MVP-5.4), and drawing a `0` there
 * would be the one lie this dashboard must not tell.
 */

/** What is drawn where a value is missing. */
export const UNAVAILABLE = '—'

const CLOCK = new Intl.DateTimeFormat('en-GB', {
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

const COUNT = new Intl.NumberFormat('en-US')

const ONE_DECIMAL = new Intl.NumberFormat('en-US', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
})

function isUsableNumber(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/**
 * @param at epoch milliseconds of an instant
 * @returns the local wall-clock time of that instant, to the second
 */
export function formatClockAt(at: number): string {
  const instant = new Date(at)
  return Number.isNaN(instant.getTime()) ? '--:--:--' : CLOCK.format(instant)
}

/**
 * @param instant ISO-8601 instant of the contract
 * @returns the local wall-clock time of that instant, to the second
 */
export function formatClock(instant: string | null | undefined): string {
  if (!instant) {
    return '--:--:--'
  }
  const at = new Date(instant)
  return Number.isNaN(at.getTime()) ? '--:--:--' : formatClockAt(at.getTime())
}

/**
 * @param value statistic or count, possibly absent
 * @returns the number grouped for reading, or the gap marker when it is absent
 */
export function formatCount(value: number | null | undefined): string {
  return isUsableNumber(value) ? COUNT.format(value) : UNAVAILABLE
}

/**
 * @param value speed in kilometres per hour, possibly absent
 * @returns the speed with one decimal and its unit
 */
export function formatSpeedKph(value: number | null | undefined): string {
  return isUsableNumber(value) ? `${ONE_DECIMAL.format(value)} km/h` : UNAVAILABLE
}

/**
 * @param seconds uptime reported by the API
 * @returns a short duration, from seconds to hours
 */
export function formatUptime(seconds: number | null | undefined): string {
  if (!isUsableNumber(seconds) || seconds < 0) {
    return UNAVAILABLE
  }
  const total = Math.floor(seconds)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const rest = total % 60
  if (hours > 0) {
    return `${hours}h ${String(minutes).padStart(2, '0')}m`
  }
  if (minutes > 0) {
    return `${minutes}m ${String(rest).padStart(2, '0')}s`
  }
  return `${rest}s`
}

/**
 * @param since epoch milliseconds of an observation, possibly absent
 * @param now the moment to measure against, injectable so a test is not a race
 * @returns how long ago that was, in words
 */
export function formatAge(since: number | null | undefined, now: number = Date.now()): string {
  if (!isUsableNumber(since)) {
    return 'never'
  }
  const seconds = Math.max(0, Math.round((now - since) / 1000))
  if (seconds < 2) {
    return 'just now'
  }
  if (seconds < 60) {
    return `${seconds}s ago`
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes}m ago`
  }
  return `${Math.floor(minutes / 60)}h ago`
}
