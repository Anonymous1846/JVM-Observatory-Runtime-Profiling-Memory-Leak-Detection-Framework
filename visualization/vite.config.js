/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.js',
  },
  server: {
    port: 5173,
    // ISSUE 10 FIX: Proxy API requests to Spring Boot backend
    // During development, Vite serves on :5173 and Spring Boot on :8080.
    // Without this proxy, the browser blocks cross-origin requests (CORS).
    // The proxy forwards /api/* requests from :5173 to :8080 transparently.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  }
})
