import { defineConfig } from 'vite'

export default defineConfig({
  // Use relative paths so Capacitor's WebView can load assets from file://
  base: '',
  publicDir: 'public',
  server: {
    port: 5173,
    host: true
  },
  build: {
    outDir: 'dist'
  }
})


