import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { DEFAULT_FLEET_OPTIONS } from '../../map/fleetPresenter'
import FleetControls from '../FleetControls.vue'

/** The panel that changes what the map draws, driven by clicks like a reader's. */
describe('FleetControls', () => {
  it('offers the bounded counts of the budget, and marks the one in force', () => {
    const wrapper = mount(FleetControls, { props: { options: { ...DEFAULT_FLEET_OPTIONS, visibleCount: 50 } } })

    const counts = wrapper.findAll<HTMLInputElement>('input[name="visible-vehicles"]')
    expect(counts.map((input) => input.element.value)).toEqual(['25', '50', '100'])
    expect(counts.map((input) => input.element.checked)).toEqual([false, true, false])
  })

  it('asks for a different count when a reader picks one', async () => {
    const wrapper = mount(FleetControls, { props: { options: DEFAULT_FLEET_OPTIONS } })

    await wrapper.findAll('input[name="visible-vehicles"]')[2].setValue()

    expect(wrapper.emitted('change')?.[0]).toEqual([{ visibleCount: 100 }])
  })

  it('turns the layers of the map on and off', async () => {
    const wrapper = mount(FleetControls, { props: { options: { ...DEFAULT_FLEET_OPTIONS, trails: true } } })

    const checkboxes = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    expect(checkboxes).toHaveLength(3)

    await checkboxes[0].setValue(false)
    await checkboxes[2].setValue(false)

    expect(wrapper.emitted('change')).toEqual([[{ trails: false }], [{ followFleet: false }]])
  })

  it('offers the palettes of the map and the density of its district', async () => {
    const wrapper = mount(FleetControls, { props: { options: DEFAULT_FLEET_OPTIONS } })

    const selects = wrapper.findAll('select')
    expect(selects).toHaveLength(2)
    expect(selects[0].findAll('option').map((option) => option.text())).toEqual([
      'Night ops',
      'Blueprint',
      'Alpine dawn',
    ])
    expect(selects[1].findAll('option').map((option) => option.text())).toEqual(['Sparse', 'Regular', 'Dense'])

    await selects[0].setValue('blueprint')
    await selects[1].setValue('dense')

    expect(wrapper.emitted('change')).toEqual([[{ themeId: 'blueprint' }], [{ density: 'dense' }]])
  })
})
