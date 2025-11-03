import { defineConfig } from 'vite'
// @ts-ignore - type resolution for plugin in config is not required
import react from '@vitejs/plugin-react'

export default defineConfig({
  // Use relative paths so Capacitor's WebView can load assets from file://
  base: '',
  publicDir: 'public',
  plugins: [react()],
  server: {
    port: 5173,
    host: true
  },
  build: {
    outDir: 'dist'
  }
})


