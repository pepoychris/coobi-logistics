import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ConnectionBadge from '../ConnectionBadge.vue'

describe('ConnectionBadge', () => {
  it('reports a live connection without offering to reopen it', () => {
    const wrapper = mount(ConnectionBadge, { props: { status: 'live', lastMessageAt: Date.now() } })

    expect(wrapper.get('.connection__label').text()).toBe('Live')
    expect(wrapper.get('.connection').classes()).toContain('connection--live')
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.get('.connection__age').text()).toBe('just now')
  })

  it('distinguishes a connection the browser is retrying from one that is gone', () => {
    const retrying = mount(ConnectionBadge, { props: { status: 'reconnecting' } })
    const gone = mount(ConnectionBadge, { props: { status: 'error' } })

    expect(retrying.get('.connection__label').text()).toBe('Reconnecting')
    expect(gone.get('.connection__label').text()).toBe('Disconnected')
    expect(gone.get('.connection').classes()).toContain('connection--error')
  })

  it('announces itself politely, so a reconnect does not interrupt a reader', () => {
    const wrapper = mount(ConnectionBadge, { props: { status: 'reconnecting' } })

    expect(wrapper.get('.connection').attributes('role')).toBe('status')
    expect(wrapper.get('.connection').attributes('aria-live')).toBe('polite')
  })

  it('asks the page to open the streams again', async () => {
    const wrapper = mount(ConnectionBadge, { props: { status: 'error' } })

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('reconnect')).toHaveLength(1)
  })
})
