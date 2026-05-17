import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const API_PORT = process.env.TEXTURE_STUDIO_API_PORT ?? '3008'
const API = `http://localhost:${API_PORT}`

export default defineConfig({
  plugins: [vue()],
  server: {
    port: Number(process.env.TEXTURE_STUDIO_PORT ?? '3007'),
    proxy: {
      '/api': API,
      '/files': API,
    },
  },
})
