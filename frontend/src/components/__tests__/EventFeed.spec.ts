import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { alert, vehicle } from '../../__tests__/support/fixtures'
import { alertEntry, vehicleEntry } from '../../stream/feedEntry'
import type { FeedEntry } from '../../stream/feedTypes'
import EventFeed from '../EventFeed.vue'

const entries: FeedEntry[] = [
  vehicleEntry(vehicle({ vehicleId: 'TRUCK-21', speed: 42 }), 1),
  alertEntry(alert({ vehicleId: 'TRUCK-92', metadata: { speed: 137.2, threshold: 120 } }), 2),
]

describe('EventFeed', () => {
  it('shows every entry of the feed, oldest first, with its time, vehicle and event', () => {
    const wrapper = mount(EventFeed, { props: { entries, status: 'live' } })

    const rows = wrapper.findAll('.feed__row')
    expect(rows).toHaveLength(2)
    expect(rows[0].get('.feed__vehicle').text()).toBe('TRUCK-21')
    expect(rows[0].get('.feed__label').text()).toBe('LOCATION_UPDATED')
    expect(rows[1].get('.feed__label').text()).toBe('SPEEDING')
    expect(rows[0].get('.feed__time').text()).toMatch(/^\d{2}:\d{2}:\d{2}$/)
    expect(wrapper.get('.feed__list').attributes('role')).toBe('log')
  })

  it('sets an alert apart from the telemetry around it', () => {
    const wrapper = mount(EventFeed, { props: { entries, status: 'live' } })

    expect(wrapper.findAll('.feed__row--alert')).toHaveLength(1)
    expect(wrapper.get('.feed__row--alert').text()).toContain('TRUCK-92')
    expect(wrapper.get('.badge--alert').text()).toBe('WARNING')
    expect(wrapper.findAll('.badge--alert')).toHaveLength(1)
  })

  it('says which window of the stream it is showing', () => {
    const wrapper = mount(EventFeed, { props: { entries, status: 'live' } })

    expect(wrapper.get('#feed-heading').text()).toBe('Live events')
    expect(wrapper.text()).toContain('Newest 100')
    expect(wrapper.text()).toContain('2 shown')
    expect(wrapper.text()).toContain('Live')
  })

  it('explains an empty feed instead of showing nothing', () => {
    const wrapper = mount(EventFeed, { props: { entries: [], status: 'loading' } })

    expect(wrapper.get('.feed__empty').text()).toContain('No events yet')
    expect(wrapper.findAll('.feed__row')).toHaveLength(0)
  })

  it('lets the reader stop following the live edge, and start again', async () => {
    const wrapper = mount(EventFeed, { props: { entries, status: 'live' } })
    const toggle = wrapper.findAll('button')[0]

    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(toggle.text()).toContain('Following')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    expect(toggle.text()).toContain('Paused')
  })

  it('asks the page to clear the feed', async () => {
    const wrapper = mount(EventFeed, { props: { entries, status: 'live' } })

    await wrapper.findAll('button')[1].trigger('click')

    expect(wrapper.emitted('clear')).toHaveLength(1)
  })
})
