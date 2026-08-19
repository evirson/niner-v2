import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Backoffice da Vetor (admin). Porta 5174 (spec §3.5) — já prevista em niner.cors.origins.
// A base-URL da API é lida em runtime (public/config.js), nunca embutida no bundle (spec §3.1).
export default defineConfig({
  plugins: [react()],
  server: { port: 5174 },
})
