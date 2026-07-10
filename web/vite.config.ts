import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// App do ERP do lojista (web). Porta 5173 (spec §3.5). A base-URL da API é lida em
// runtime (public/config.js) — não embutida no bundle (spec §3.1).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 },
})
