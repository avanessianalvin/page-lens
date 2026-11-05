import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// Dev: `npm run dev` on http://localhost:5173 proxies API calls to the Spring app on :8080.
// Build: output goes straight into app-web's static resources, so the jar serves the dashboard.
export default defineConfig({
  plugins: [vue(), vueDevTools()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://127.0.0.1:8080',
      '/auth': 'http://127.0.0.1:8080',
      '/actuator': 'http://127.0.0.1:8080',
    },
  },
  build: {
    outDir: '../app-web/src/main/resources/static',
    emptyOutDir: true,
  },
})
