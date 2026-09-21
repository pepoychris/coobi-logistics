import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

/** Origin the dev and preview servers proxy the API to when nothing overrides it. */
const DEFAULT_API_PROXY_TARGET = 'http://localhost:8082'

/**
 * Build and test configuration of the dashboard.
 *
 * Two things here are part of the contract with the API rather than a matter of
 * taste. The proxy makes `/api` reach the local API from the dev and preview
 * servers, which is what lets the dashboard talk to `localhost:8082` from a
 * browser without the API having to enable CORS. And the test environment is
 * `jsdom`, because the dashboard is DOM code and the tests exercise real DOM
 * nodes rather than a stub of them.
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_API_PROXY_TARGET?.trim() || DEFAULT_API_PROXY_TARGET
  const proxy = {
    '/api': {
      target: proxyTarget,
      changeOrigin: true,
    },
  }

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy,
    },
    preview: {
      port: 4173,
      proxy,
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
    },
    test: {
      environment: 'jsdom',
      include: ['src/**/*.spec.ts'],
      restoreMocks: true,
    },
  }
})
