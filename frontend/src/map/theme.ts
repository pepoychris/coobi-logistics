/**
 * The palettes of the procedural fleet map.
 *
 * The map is drawn from data rather than from tiles, so its colours are data
 * too: one object per palette, read by the renderer and by the panel around it,
 * which turns the same tokens into CSS variables. A theme changes what the
 * depot district looks like and nothing else - no value, no measurement and no
 * state of the pipeline depends on which one is selected.
 */

/** Identifier of a palette, as the control that picks one names it. */
export type FleetThemeId = 'night-ops' | 'blueprint' | 'alpine-dawn'

/**
 * One palette.
 *
 * Every field is a CSS colour, so the same token can be handed to three.js
 * (`new THREE.Color(token)`) and written into a CSS custom property.
 */
export interface FleetTheme {
  id: FleetThemeId
  /** Name a reader picks in the control. */
  label: string
  /** One line on what the palette is for. */
  description: string
  /** Ground the district sits on. */
  background: string
  /** Filled district blocks. */
  block: string
  /** Outlines of the district blocks. */
  blockEdge: string
  /** Roofs of the buildings standing on the blocks. */
  building: string
  /** The offset a building throws on the ground, which is what makes it look tall. */
  buildingShadow: string
  /** Green areas inside the district. */
  park: string
  /** Water, when the palette has a channel. */
  water: string
  /** The depot the fleet is anchored to. */
  depot: string
  /** Road surface. */
  road: string
  /** Lane markings on the arterial roads. */
  marking: string
  /** Vehicles that are moving. */
  moving: string
  /** Vehicles that are stopped. */
  stopped: string
  /** The trail a moving vehicle leaves behind it. */
  trail: string
  /** The faint cloud of the vehicles outside the rendering budget. */
  backgroundFleet: string
  /** The ring drawn around the vehicle a reader selected. */
  selection: string
  /** Ink used by the panel chrome that follows the palette. */
  ink: string
}

export const FLEET_THEMES: readonly FleetTheme[] = [
  {
    id: 'night-ops',
    label: 'Night ops',
    description: 'Depot at night: graphite ground, green for moving, amber for the depot.',
    background: '#0c100f',
    block: '#1a2321',
    blockEdge: '#0f1614',
    building: '#2d3d38',
    buildingShadow: '#070b0a',
    park: '#1b3025',
    water: '#152736',
    depot: '#463419',
    road: '#2b3532',
    marking: '#6b7d78',
    moving: '#4ade80',
    stopped: '#8fa0a2',
    trail: '#2f6b4c',
    backgroundFleet: '#2c4a3d',
    selection: '#ffb454',
    ink: '#e6efe9',
  },
  {
    id: 'blueprint',
    label: 'Blueprint',
    description: 'Survey sheet: paper ground, ink linework, safety orange for the depot.',
    background: '#f4f5f2',
    block: '#e8ebe5',
    blockEdge: '#c9cfc4',
    building: '#fbfcfa',
    buildingShadow: '#b9c0b4',
    park: '#dbe8d6',
    water: '#d3e3ec',
    depot: '#fadfc0',
    road: '#dfe3da',
    marking: '#8d968a',
    moving: '#14684a',
    stopped: '#7c8479',
    trail: '#4d8f74',
    backgroundFleet: '#a9b5a7',
    selection: '#d2691e',
    ink: '#20261f',
  },
  {
    id: 'alpine-dawn',
    label: 'Alpine dawn',
    description: 'Cold highland light: charcoal ground, amber fleet, coral trails.',
    background: '#111015',
    block: '#191821',
    blockEdge: '#262535',
    building: '#2a2939',
    buildingShadow: '#0b0a10',
    park: '#16211f',
    water: '#161f2d',
    depot: '#33241c',
    road: '#2b2a36',
    marking: '#6b6a7d',
    moving: '#ffd166',
    stopped: '#8b8a9c',
    trail: '#7a5230',
    backgroundFleet: '#4a4160',
    selection: '#f78c6b',
    ink: '#eeecf5',
  },
]

/** The palette a dashboard opens with. */
export const DEFAULT_THEME_ID: FleetThemeId = 'night-ops'

/**
 * Whether a value is one of the palettes this module ships.
 *
 * Used where a stored or configured value has to be trusted before it is used,
 * so an unknown theme falls back to the default instead of breaking the map.
 */
export function isFleetThemeId(value: unknown): value is FleetThemeId {
  return typeof value === 'string' && FLEET_THEMES.some((theme) => theme.id === value)
}

/**
 * @param id identifier, possibly unknown
 * @returns the palette with that id, or the default one
 */
export function fleetTheme(id: unknown): FleetTheme {
  return FLEET_THEMES.find((theme) => theme.id === id) ?? FLEET_THEMES[0]
}

/**
 * The palette as CSS custom properties, so the chrome of the panel wears the
 * same colours as the scene it frames.
 *
 * @param theme palette to translate
 * @returns the properties, ready to bind to `:style`
 */
export function themeCssVars(theme: FleetTheme): Record<string, string> {
  return {
    '--fleet-ground': String(theme.background),
    '--fleet-road': String(theme.road),
    '--fleet-marking': String(theme.marking),
    '--fleet-depot': String(theme.depot),
    '--fleet-moving': String(theme.moving),
    '--fleet-stopped': String(theme.stopped),
    '--fleet-trail': String(theme.trail),
    '--fleet-faint': String(theme.backgroundFleet),
    '--fleet-selection': String(theme.selection),
    '--fleet-ink': String(theme.ink),
  }
}
