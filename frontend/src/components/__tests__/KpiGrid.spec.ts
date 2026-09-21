import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { statistics as statisticsFixture } from '../../__tests__/support/fixtures'
import KpiGrid from '../KpiGrid.vue'

describe('KpiGrid', () => {
  it('loads with placeholders rather than with numbers nobody measured', () => {
    const wrapper = mount(KpiGrid, { props: { statistics: null, status: 'loading', loading: true } })

    expect(wrapper.get('.kpis__list').attributes('aria-busy')).toBe('true')
    expect(wrapper.findAll('.skeleton')).toHaveLength(4)
    const values = wrapper.findAll('.kpi__value').map((value) => value.text()).join(' ')
    expect(values).not.toMatch(/\d/)
    expect(values).toContain('is loading')
  })

  it('shows the five KPIs of the milestone, from the object the backend sent', () => {
    const wrapper = mount(KpiGrid, {
      props: { statistics: statisticsFixture(), status: 'live', loading: false },
    })

    const labels = wrapper.findAll('.kpi__label').map((label) => label.text())
    expect(labels).toEqual([
      'Events processed',
      'Events per second',
      'Active vehicles',
      'Alerts generated',
      'System status',
    ])

    const values = wrapper.findAll('.kpi__value').map((value) => value.text())
    expect(values[0]).toBe('1,234,567')
    expect(values[1]).toBe('812/s')
    expect(values[2]).toBe('42')
    expect(values[3]).toBe('9')
    expect(values[4]).toBe('Live')
    expect(wrapper.get('.kpis__list').attributes('aria-busy')).toBe('false')
  })

  it('names the uptime of the API instance it is reading', () => {
    const wrapper = mount(KpiGrid, {
      props: { statistics: statisticsFixture({ uptimeSeconds: 3_725 }), status: 'live', loading: false },
    })

    expect(wrapper.text()).toContain('Uptime of this API instance: 1h 02m')
  })

  it('draws a gap where the API reported no value', () => {
    const wrapper = mount(KpiGrid, {
      props: {
        statistics: statisticsFixture({ processedEvents: null, eventsPerSecond: null }),
        status: 'live',
        loading: false,
      },
    })

    const first = wrapper.findAll('.kpi')[0]
    expect(first.attributes('data-unavailable')).toBe('true')
    expect(first.get('.kpi__value').text()).toBe('—')
    expect(wrapper.findAll('.kpi')[1].get('.kpi__value').text()).toBe('—')
    expect(wrapper.findAll('.kpi')[2].get('.kpi__value').text()).toBe('42')
  })

  it('reports the state of the connection as a KPI of its own', () => {
    const wrapper = mount(KpiGrid, { props: { statistics: null, status: 'error', loading: true } })

    expect(wrapper.get('.kpi--status').text()).toContain('Disconnected')
    expect(wrapper.find('.kpi--error').exists()).toBe(true)
  })
})
