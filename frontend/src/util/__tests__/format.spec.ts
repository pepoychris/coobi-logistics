import { describe, expect, it } from 'vitest'

import { formatAge, formatClock, formatCount, formatSpeedKph, formatUptime, UNAVAILABLE } from '../format'

describe('formatClock', () => {
  it('shows the local wall-clock time of an instant', () => {
    const instant = '2026-09-21T09:15:03.123Z'
    const at = new Date(instant)
    const expected = [at.getHours(), at.getMinutes(), at.getSeconds()]
      .map((part) => String(part).padStart(2, '0'))
      .join(':')

    expect(formatClock(instant)).toBe(expected)
  })

  it('shows a gap instead of a made-up time', () => {
    expect(formatClock(null)).toBe('--:--:--')
    expect(formatClock(undefined)).toBe('--:--:--')
    expect(formatClock('')).toBe('--:--:--')
    expect(formatClock('yesterday')).toBe('--:--:--')
  })
})

describe('formatCount', () => {
  it('groups a number for reading', () => {
    expect(formatCount(1_234_567)).toBe('1,234,567')
    expect(formatCount(0)).toBe('0')
  })

  it('draws a gap where the API reported nothing', () => {
    expect(formatCount(null)).toBe(UNAVAILABLE)
    expect(formatCount(undefined)).toBe(UNAVAILABLE)
    expect(formatCount(Number.NaN)).toBe(UNAVAILABLE)
  })
})

describe('formatSpeedKph', () => {
  it('writes one decimal and the unit', () => {
    expect(formatSpeedKph(12.34)).toBe('12.3 km/h')
    expect(formatSpeedKph(null)).toBe(UNAVAILABLE)
  })
})

describe('formatUptime', () => {
  it('writes a short duration', () => {
    expect(formatUptime(9)).toBe('9s')
    expect(formatUptime(65)).toBe('1m 05s')
    expect(formatUptime(3_725)).toBe('1h 02m')
    expect(formatUptime(null)).toBe(UNAVAILABLE)
  })
})

describe('formatAge', () => {
  const now = Date.UTC(2026, 8, 21, 9, 15, 10)

  it('says how long ago an observation was', () => {
    expect(formatAge(now - 500, now)).toBe('just now')
    expect(formatAge(now - 12_000, now)).toBe('12s ago')
    expect(formatAge(now - 180_000, now)).toBe('3m ago')
    expect(formatAge(now - 7_200_000, now)).toBe('2h ago')
  })

  it('says never when nothing was ever observed', () => {
    expect(formatAge(null, now)).toBe('never')
  })
})
