/// <reference types="vite/client" />

/**
 * The build-time environment of the dashboard, narrowed to the two variables it
 * reads. Both are documented in `.env.example`, which is the source of truth of
 * the environment contract of this package.
 */
interface ImportMetaEnv {
  /** Origin of the logistics API; empty means the origin that served the app. */
  readonly VITE_API_BASE_URL?: string
  /** Origin the dev and preview servers proxy `/api` to. */
  readonly VITE_API_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
