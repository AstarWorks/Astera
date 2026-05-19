import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const API_PORT = process.env.ASTERA_STUDIO_API_PORT ?? '3008'
const API = `http://localhost:${API_PORT}`

export default defineConfig({
  plugins: [vue()],
  server: {
    port: Number(process.env.ASTERA_STUDIO_PORT ?? '3007'),
    strictPort: true,
    proxy: {
      '/api': API,
      '/files': API,
      '/ws': { target: API, ws: true, changeOrigin: true },
    },
  },
})
