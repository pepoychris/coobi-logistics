/**
 * Where the dashboard reaches the logistics API.
 *
 * The default is the origin that served the dashboard, which is what makes the
 * documented local setup work in a browser: the app calls `/api/v1/...` on
 * itself and the Vite dev and preview servers proxy that path to the API of the
 * local stack. `VITE_API_BASE_URL` replaces the origin when the API lives
 * somewhere else and is reachable from the browser.
 *
 * The value is read on every call rather than captured once, so the URL the
 * dashboard talks to is the one configured for the build it is running, and a
 * test can point it somewhere else without reloading a module.
 */

/** The API version prefix every endpoint shares. */
const API_PREFIX = '/api/v1'

/** The base URL as configured, without a trailing slash; empty means "this origin". */
export function apiBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL
  if (typeof configured !== 'string') {
    return ''
  }
  return configured.trim().replace(/\/+$/, '')
}

/**
 * @param path path of the endpoint below the version prefix, starting with `/`
 * @returns the absolute or same-origin URL of that endpoint
 */
export function apiUrl(path: string): string {
  return `${apiBaseUrl()}${API_PREFIX}${path}`
}

/**
 * The API origin as it can be shown to a reader, including the local default,
 * which is not empty but "the same origin".
 */
export function apiTargetLabel(): string {
  return apiBaseUrl() || 'this origin (proxied to the API)'
}
